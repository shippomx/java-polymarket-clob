package com.polymarket.clob.deposit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.polymarket.clob.gamma.GammaSession;
import com.polymarket.clob.http.JsonCodec;
import com.polymarket.clob.model.Address;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Polymarket Relayer V2 客户端：4 个端点 —— /submit、/nonce、/transaction（轮询）。
 * 鉴权用 GammaSession 的 cookie。Wire body 字段顺序与 docs/EOA_TO_ORDER.md §5 一致。
 */
public final class DepositWalletRelayer {

    private final URI baseUrl;
    private final HttpClient http;
    private final GammaSession session;
    private final ObjectMapper mapper;

    public DepositWalletRelayer(URI baseUrl, HttpClient http, GammaSession session) {
        this.baseUrl = baseUrl;
        this.http = http;
        this.session = session;
        this.mapper = JsonCodec.objectMapper();
    }

    public String submitWalletCreate(Address eoa, Address factory) throws IOException, InterruptedException {
        ObjectNode body = mapper.createObjectNode();
        body.put("type", "WALLET-CREATE");
        body.put("from", eoa.toLowerHex());
        body.put("to", factory.toLowerHex());
        return submitAndExtractTxId(mapper.writeValueAsString(body));
    }

    public String submitBatch(SignedBatch batch) throws IOException, InterruptedException {
        ObjectNode body = mapper.createObjectNode();
        body.put("type", "WALLET");
        body.put("from", batch.eoa().toLowerHex());
        body.put("to", batch.factory().toLowerHex());
        body.put("nonce", batch.nonce().toString());
        body.put("signature", "0x" + HexFormat.of().formatHex(batch.signature65()));

        ObjectNode params = body.putObject("depositWalletParams");
        params.put("depositWallet", batch.wallet().toLowerHex());
        params.put("deadline", batch.deadline().toString());
        ArrayNode calls = params.putArray("calls");
        for (Call c : batch.calls()) {
            ObjectNode n = calls.addObject();
            n.put("target", c.target().toLowerHex());
            n.put("value", c.value().toString());
            n.put("data", "0x" + HexFormat.of().formatHex(c.data()));
        }
        return submitAndExtractTxId(mapper.writeValueAsString(body));
    }

    public BigInteger getRelayerNonce(Address eoa) throws IOException, InterruptedException {
        URI uri = URI.create(baseUrl + "/nonce?address=" + eoa.toLowerHex() + "&type=WALLET");
        HttpResponse<String> resp = sendGet(uri);
        ensureOk(resp, "/nonce");
        JsonNode node = mapper.readTree(resp.body());
        if (node.isObject()) node = node.path("nonce");
        if (node.isNumber()) return node.bigIntegerValue();
        if (node.isTextual()) return new BigInteger(node.asText().trim());
        throw new IOException("/nonce 响应无法解析：" + resp.body());
    }

    public RelayerTxResult getTransaction(String txId) throws IOException, InterruptedException {
        URI uri = URI.create(baseUrl + "/transaction?id=" + URLEncoder.encode(txId, StandardCharsets.UTF_8));
        HttpResponse<String> resp = sendGet(uri);
        ensureOk(resp, "/transaction");
        return parseTxResult(resp.body(), txId);
    }

    public RelayerTxResult waitForTx(String txId, Duration pollInterval, int maxAttempts)
            throws IOException, InterruptedException {
        for (int i = 0; i < maxAttempts; i++) {
            Thread.sleep(pollInterval.toMillis());
            RelayerTxResult r = getTransaction(txId);
            if (r.isTerminal()) {
                if (!r.isConfirmed()) throw new RelayerTxFailedException(r);
                return r;
            }
        }
        throw new IOException("relayer tx 未达终态 txId=" + txId);
    }

    // ---- 内部 ----

    private String submitAndExtractTxId(String json) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/submit"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Cookie", session.cookieHeader())
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("relayer /submit HTTP " + resp.statusCode() + " body=" + resp.body());
        }
        RelayerTxResult r = parseTxResult(resp.body(), null);
        if (r.txId() == null || r.txId().isBlank()) {
            throw new IOException("relayer /submit 未返回 transactionID: " + resp.body());
        }
        return r.txId();
    }

    private HttpResponse<String> sendGet(URI uri) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Cookie", session.cookieHeader())
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static void ensureOk(HttpResponse<String> resp, String tag) throws IOException {
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("relayer " + tag + " HTTP " + resp.statusCode() + " body=" + resp.body());
        }
    }

    private RelayerTxResult parseTxResult(String body, String fallbackTxId) throws IOException {
        JsonNode node = mapper.readTree(body);
        if (node.isArray() && node.size() > 0) node = node.get(0);

        String txId  = textOrNull(node, "transactionID", "transactionId");
        if (txId == null) txId = fallbackTxId;
        String state = textOrNull(node, "state");
        String hash  = textOrNull(node, "transactionHash", "hash");
        String error = textOrNull(node, "errorMsg", "error", "reason", "failureReason", "message");

        if (state != null && state.startsWith("STATE_")) state = state.substring("STATE_".length());
        if (state != null) state = state.toUpperCase();
        return new RelayerTxResult(txId, state, hash, error);
    }

    private static String textOrNull(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull()) {
                String s = v.isTextual() ? v.asText() : v.toString();
                if (!s.isBlank() && !"\"\"".equals(s) && !"null".equals(s)) return s;
            }
        }
        return null;
    }
}
