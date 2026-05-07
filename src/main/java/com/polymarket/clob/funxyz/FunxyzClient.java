package com.polymarket.clob.funxyz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.polymarket.clob.http.JsonCodec;
import com.polymarket.clob.model.Address;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * fun.xyz 法币入金地址客户端。封装单接口 {@code POST /v1/eoa}：给定 EOA + recipient，
 * 返回该 EOA 在 fun.xyz 上固定映射的四链入金中转地址。
 *
 * <p>线程安全：本类无可变状态，{@link java.net.http.HttpClient} 自身线程安全。同一实例可并发调用。</p>
 */
public final class FunxyzClient {

    private static final Logger log = LoggerFactory.getLogger(FunxyzClient.class);

    private static final String ORIGIN  = "https://polymarket.com";
    private static final String REFERER = "https://polymarket.com/";
    private static final String TO_CHAIN_ID    = "137";
    private static final String USDC_E_POLYGON = "0x2791bca1f2de4661ed88a30c99a7a9449aa84174";

    /**
     * 占位 clientMetadata。fun.xyz 不校验内容但缺整字段会 400。
     * 来自 2026-05-07 polymarket.com 前端抓包，最小化保留结构骨架。
     */
    private static final String CLIENT_METADATA_STUB = """
            {"id":"","startTimestampMs":0,"finalDollarValue":0,"latestQuote":null,"depositAddress":null,
             "initSettings":{"config":{"targetAsset":"0x","targetChain":"","targetAssetTicker":"","checkoutItemTitle":""}},
             "selectedSourceAssetInfo":{"address":"0x","symbol":"","chainId":"","iconSrc":null},
             "selectedPaymentMethodInfo":{"paymentMethod":"token_transfer","title":"QR Code Transfer","description":""}}
            """;

    private final FunxyzConfig cfg;
    private final ObjectMapper mapper;

    public FunxyzClient(FunxyzConfig cfg) {
        this.cfg = Objects.requireNonNull(cfg, "cfg");
        this.mapper = JsonCodec.objectMapper();
    }

    /**
     * 取 fun.xyz 给该 EOA 的固定四链入金地址。
     *
     * @param eoa       用户 EOA（fun.xyz 按此持久映射）
     * @param recipient 链上 USDC 最终转发目标（一般是 Polymarket Deposit Wallet）
     * @return 固定映射的四链地址
     * @throws IllegalArgumentException 任一参数为 {@code null}（同步抛出，不进 future）
     */
    public CompletableFuture<DepositAddresses> getDepositAddresses(Address eoa, Address recipient) {
        if (eoa == null) throw new IllegalArgumentException("eoa must not be null");
        if (recipient == null) throw new IllegalArgumentException("recipient must not be null");

        String body = buildRequestBody(eoa, recipient);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(cfg.baseUrl().resolve("/v1/eoa"))
                .header("content-type", "application/json")
                .header("origin", ORIGIN)
                .header("referer", REFERER)
                .header("x-api-key", cfg.apiKey())
                .timeout(cfg.requestTimeout())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        log.info("funxyz POST /v1/eoa eoa={} recipient={}", eoa.toLowerHex(), recipient.toLowerHex());

        return cfg.httpClient()
                .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .handle((resp, err) -> {
                    if (err != null) {
                        // CompletableFuture.handle() may receive CompletionException wrapping the cause
                        // depending on which internal stage of sendAsync fails. Unwrap defensively.
                        Throwable cause = err instanceof CompletionException ce && ce.getCause() != null
                                ? ce.getCause() : err;
                        if (cause instanceof HttpTimeoutException) {
                            throw new FunxyzException(
                                    "funxyz request timed out after "
                                            + cfg.requestTimeout().toSeconds() + "s",
                                    -1, cause);
                        }
                        throw new FunxyzException(
                                "funxyz request failed: " + cause.getMessage(), -1, cause);
                    }
                    return parseResponse(resp);
                });
    }

    private String buildRequestBody(Address eoa, Address recipient) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("userId", eoa.toHex());
            root.put("recipientAddr", recipient.toHex());
            root.put("toChainId", TO_CHAIN_ID);
            root.put("toTokenAddress", USDC_E_POLYGON);
            root.set("clientMetadata", mapper.readTree(CLIENT_METADATA_STUB));
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new FunxyzException("failed to build request body", -1, e);
        }
    }

    private DepositAddresses parseResponse(HttpResponse<String> resp) {
        if (resp.statusCode() != 200) {
            String snippet = truncate(resp.body(), 500);
            log.warn("funxyz POST /v1/eoa returned {}: {}", resp.statusCode(), snippet);
            throw new FunxyzException(
                    "funxyz POST /v1/eoa returned " + resp.statusCode() + ": " + snippet,
                    resp.statusCode());
        }

        JsonNode root;
        try {
            root = mapper.readTree(resp.body());
        } catch (Exception e) {
            throw new FunxyzException(
                    "funxyz returned malformed JSON: " + truncate(resp.body(), 200),
                    200, e);
        }

        if (root.path("blocked").asBoolean(false)) {
            throw new FunxyzException(FunxyzException.BLOCKED_MESSAGE, 200);
        }

        String depositAddr = root.path("depositAddr").asText("");
        // 必须是 "0x" + 40 hex 字符 = 42 字符
        if (!depositAddr.startsWith("0x") || depositAddr.length() != 42) {
            throw new FunxyzException("funxyz response missing or malformed depositAddr", 200);
        }

        return new DepositAddresses(
                Address.fromHex(depositAddr),
                root.path("solanaAddr").asText(""),
                root.path("tronAddr").asText(""),
                root.path("btcAddrSegwit").asText("")
        );
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
