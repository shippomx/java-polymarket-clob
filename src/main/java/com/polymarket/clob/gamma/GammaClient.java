package com.polymarket.clob.gamma;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.http.JsonCodec;
import com.polymarket.clob.model.Address;
import org.web3j.crypto.Sign;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Polymarket Gamma 鉴权客户端：SIWE 登录与用户档案管理。
 */
public final class GammaClient {

    private final URI baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public GammaClient(URI baseUrl, HttpClient http) {
        this.baseUrl = baseUrl;
        this.http = http;
        this.mapper = JsonCodec.objectMapper();
    }

    public CompletableFuture<GammaSession> loginWithSiwe(Signer eoa, long chainId) {
        // 1. GET /nonce → cookie₀ + nonce 文本
        return getJson(baseUrl.resolve("/nonce"), null).thenCompose(nonceResp -> {
            if (nonceResp.status() < 200 || nonceResp.status() >= 300) {
                throw new GammaAuthException("/nonce failed status=" + nonceResp.status()
                        + " body=" + nonceResp.body());
            }
            String nonce;
            try {
                nonce = mapper.readTree(nonceResp.body()).path("nonce").asText("");
            } catch (Exception e) {
                throw new GammaAuthException("/nonce body parse failure", e);
            }
            if (nonce.isEmpty()) throw new GammaAuthException("/nonce missing nonce");
            if (!nonce.matches("[A-Za-z0-9_\\-]+")) {
                throw new GammaAuthException("/nonce value contains unexpected chars: " + nonce);
            }

            String cookie0 = mergeSetCookie("", nonceResp.setCookies());

            // 2. SIWE 文本 + EIP-191 personal_sign
            Instant issued = Instant.now();
            Instant expiry = issued.plusSeconds(7L * 24 * 3600);
            String siwe = SiweMessage.build(eoa.address(), chainId, nonce, issued, expiry);

            byte[] digest = personalSignDigest(siwe);
            return eoa.signHash(digest).thenCompose(sig65 -> {
                String sigHex = "0x" + HexFormat.of().formatHex(sig65);
                String payloadJson = buildLoginPayload(eoa.address(), chainId, nonce, issued, expiry);
                // Token format matches TS: Buffer.from(`${payload}:::${signature}`).toString("base64")
                // The entire "payload:::0xSIG" string is base64-encoded; no plaintext ::: in the header.
                String authToken = Base64.getEncoder().encodeToString(
                        (payloadJson + ":::" + sigHex).getBytes(StandardCharsets.UTF_8));

                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Authorization", "Bearer " + authToken);
                if (!cookie0.isBlank()) {
                    headers.put("Cookie", cookie0);
                }

                return getJson(baseUrl.resolve("/login"), headers).thenApply(loginResp -> {
                    if (loginResp.status() < 200 || loginResp.status() >= 300) {
                        throw new GammaAuthException("/login failed status=" + loginResp.status()
                                + " body=" + loginResp.body());
                    }
                    String cookieFinal = mergeSetCookie(cookie0, loginResp.setCookies());
                    return new GammaSession(cookieFinal, expiry);
                });
            });
        });
    }

    public CompletableFuture<Boolean> profileExists(GammaSession s, Address eoa) {
        URI uri = baseUrl.resolve("/users?address=" + eoa.toHex());
        Map<String, String> headers = Map.of("Cookie", s.cookieHeader());
        return getJson(uri, headers).thenApply(resp -> {
            if (resp.status() == 404) return false;
            if (resp.status() < 200 || resp.status() >= 300) {
                throw new GammaAuthException("/users status=" + resp.status() + " body=" + resp.body());
            }
            try {
                JsonNode root = mapper.readTree(resp.body());
                if (root.isArray()) return root.size() > 0;
                if (root.isObject()) return root.has("id") || root.has("proxyWallet") || root.has("users");
                return false;
            } catch (Exception e) {
                throw new GammaAuthException("/users parse failure", e);
            }
        });
    }

    public CompletableFuture<Void> createProfile(GammaSession s, Address eoa, Address proxyWallet) {
        long ts = Instant.now().toEpochMilli();
        String name = proxyWallet.toHex() + "-" + ts;
        String body = "{"
                + "\"displayUsernamePublic\":true,"
                + "\"emailOptIn\":false,"
                + "\"walletActivated\":false,"
                + "\"name\":\"" + name + "\","
                + "\"pseudonym\":\"" + proxyWallet.toHex() + "\","
                + "\"proxyWallet\":\"" + proxyWallet.toHex() + "\","
                + "\"users\":[{"
                + "\"address\":\"" + eoa.toHex() + "\","
                + "\"isExternalAuth\":true,"
                + "\"proxyWallet\":\"" + proxyWallet.toHex() + "\","
                + "\"username\":\"" + name + "\","
                + "\"provider\":\"metamask\","
                + "\"preferences\":[],"
                + "\"walletPreferences\":[{\"advancedMode\":false,\"customGasPrice\":\"30\",\"gasPreference\":\"fast\"}]"
                + "}]"
                + "}";
        URI uri = baseUrl.resolve("/profiles");
        Map<String, String> headers = Map.of("Cookie", s.cookieHeader());
        return postJson(uri, body, headers).thenApply(resp -> {
            if (resp.status() < 200 || resp.status() >= 300) {
                throw new GammaAuthException("/profiles status=" + resp.status() + " body=" + resp.body());
            }
            return (Void) null;
        });
    }

    public CompletableFuture<Void> ensureProfile(GammaSession s, Address eoa, Address proxyWallet) {
        return profileExists(s, eoa).thenCompose(exists ->
                exists ? CompletableFuture.completedFuture(null)
                       : createProfile(s, eoa, proxyWallet));
    }

    // ---- 内部工具 ----

    record HttpResp(int status, String body, List<String> setCookies) {}

    CompletableFuture<HttpResp> getJson(URI uri, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET();
        if (headers != null) headers.forEach(b::header);
        return http.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> new HttpResp(r.statusCode(), r.body(),
                        r.headers().allValues("Set-Cookie")));
    }

    CompletableFuture<HttpResp> postJson(URI uri, String json, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (headers != null) headers.forEach(b::header);
        return http.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> new HttpResp(r.statusCode(), r.body(),
                        r.headers().allValues("Set-Cookie")));
    }

    /**
     * EIP-191 personal_sign digest using web3j's well-tested implementation.
     * Prepends "\x19Ethereum Signed Message:\n{len}" and Keccak-256 hashes the result.
     */
    static byte[] personalSignDigest(String message) {
        return Sign.getEthereumMessageHash(message.getBytes(StandardCharsets.UTF_8));
    }

    static String buildLoginPayload(Address eoa, long chainId, String nonce,
                                    Instant issued, Instant expiry) {
        // 字段顺序锁定，以与 TS 实现一致（base64 内容必须可被 gamma server 解码）
        return "{\"domain\":\"" + SiweMessage.DOMAIN + "\","
                + "\"address\":\"" + eoa.toHex() + "\","
                + "\"statement\":\"" + SiweMessage.STATEMENT + "\","
                + "\"uri\":\"" + SiweMessage.URI + "\","
                + "\"version\":\"1\","
                + "\"chainId\":" + chainId + ","
                + "\"nonce\":\"" + nonce + "\","
                + "\"issuedAt\":\"" + issued + "\","
                + "\"expirationTime\":\"" + expiry + "\"}";
    }

    /** 合并 Set-Cookie 头到一个 "name=value; name2=value2" 字符串。后写入覆盖先写入。 */
    static String mergeSetCookie(String existing, List<String> setCookieHeaders) {
        Map<String, String> map = new LinkedHashMap<>();
        if (existing != null && !existing.isBlank()) {
            for (String part : existing.split("; ")) {
                int eq = part.indexOf('=');
                if (eq > 0) map.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        for (String header : setCookieHeaders) {
            String first = header.split(";", 2)[0];
            int eq = first.indexOf('=');
            if (eq > 0) map.put(first.substring(0, eq).trim(), first.substring(eq + 1).trim());
        }
        return map.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("; "));
    }
}
