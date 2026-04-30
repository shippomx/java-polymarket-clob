package com.polymarket.clob.example;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.polymarket.clob.model.Address;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Polymarket Bridge 客户端（{@code https://bridge.polymarket.com}，gasless 充值/提现专用）。
 *
 * <p>Bridge 是 Polymarket 独立的桥服务，与 CLOB、Relayer 都不在一个 host：
 * <ul>
 *   <li>无需认证；上游用 CDN 缓存，不会校验签名头；</li>
 *   <li>{@code POST /deposit} —— 给某个 Polymarket Safe 派一组**专属**充值地址；用户从任意支持链
 *       （Ethereum / Polygon / Arbitrum / Base / Solana / Bitcoin）打过来的资产会被后端中转商
 *       自动 swap 成 Polygon 上的 USDC.e 并存入该 Safe；</li>
 *   <li>{@code GET /supported-assets} —— 列出当前桥支持的链 + token + 最低充值金额；</li>
 *   <li>{@code GET /status/{address}} —— 查询某个充值地址名下所有 inbound 交易的状态。</li>
 * </ul>
 * 该地址<b>一对一绑定</b>这个 Safe，可以缓存复用；同一个 Safe 调多次 {@code /deposit} 通常会拿回
 * 同一组地址。
 *
 * <p>本类只覆盖最小可用面（{@code /deposit}）；其他端点等到对应业务需求再补。</p>
 */
public final class PolymarketBridge {

    /** 默认线上 host；自托管或测试可注入其他地址。 */
    public static final String DEFAULT_HOST = "https://bridge.polymarket.com";

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public PolymarketBridge() {
        this(DEFAULT_HOST);
    }

    public PolymarketBridge(String baseUrl) {
        this.baseUrl = trimTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    /**
     * 向 Bridge 申请一组多链充值地址。
     *
     * @param safe Polymarket Safe 钱包地址（funder），即「希望钱最终流入」的目的地址
     * @return 一组 EVM/SVM/BTC 充值地址 + 可选 {@code note}
     */
    public DepositResponse createDepositAddresses(Address safe) throws IOException, InterruptedException {
        Objects.requireNonNull(safe, "safe");
        ObjectNode body = mapper.createObjectNode();
        body.put("address", safe.toLowerHex());
        String json = mapper.writeValueAsString(body);

        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/deposit"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        // /deposit 成功 status 是 201；统一接受 2xx
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("bridge POST /deposit 失败 status=" + resp.statusCode()
                    + " body=" + resp.body());
        }
        return mapper.readValue(resp.body(), DepositResponse.class);
    }

    private static String trimTrailingSlash(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    // =========================================================
    //  响应模型
    // =========================================================

    /** {@code POST /deposit} 响应体；对齐 Rust {@code bridge::types::DepositResponse}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DepositResponse(
            @JsonProperty("address") DepositAddresses addresses,
            @JsonProperty("note") String note) {

        /**
         * 列出一行行 "{chain}: {addr}" 文本，便于直接打印；不暴露 SVM/BTC 时也能优雅退化。
         */
        public List<String> formatLines() {
            DepositAddresses a = addresses;
            if (a == null) return List.of();
            return List.of(
                    "EVM (Ethereum/Polygon/Arbitrum/Base/...): " + nullToDash(a.evm),
                    "SVM (Solana):                              " + nullToDash(a.svm),
                    "BTC (Bitcoin):                             " + nullToDash(a.btc));
        }

        private static String nullToDash(String s) {
            return (s == null || s.isBlank()) ? "(unavailable)" : s;
        }
    }

    /**
     * 三链充值地址。EVM 用 {@link String} 而非 {@link Address}：服务端在不支持时可能返回空串
     * 或非 checksum 大小写，强约束反而容易破坏整个反序列化。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DepositAddresses(
            @JsonProperty("evm") String evm,
            @JsonProperty("svm") String svm,
            @JsonProperty("btc") String btc) {
    }
}
