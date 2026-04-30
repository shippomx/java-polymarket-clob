package com.polymarket.clob.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.polymarket.clob.api.model.FeeRateResponse;
import com.polymarket.clob.api.model.MarketResponse;
import com.polymarket.clob.api.model.MidpointResponse;
import com.polymarket.clob.api.model.NegRiskResponse;
import com.polymarket.clob.api.model.OrderBookSnapshot;
import com.polymarket.clob.api.model.PriceResponse;
import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.api.model.TickSizeResponse;
import com.polymarket.clob.http.HttpTransport;
import com.polymarket.clob.order.TickSize;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * {@link MarketDataApi} 的默认实现，所有调用透传给 {@link HttpTransport}。
 */
public final class MarketDataApiImpl implements MarketDataApi {

    private final HttpTransport transport;

    public MarketDataApiImpl(HttpTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public CompletableFuture<String> ok() {
        // 用 "/" 而非 ""，避免依赖 URI.resolve("") 的隐式语义；
        // HttpTransport.resolve 对二者行为一致
        return transport.get("/", Map.of(), Map.of(), new TypeReference<String>() {});
    }

    @Override
    public CompletableFuture<Long> serverTime() {
        return transport.get("time", Map.of(), Map.of(), new TypeReference<Long>() {});
    }

    @Override
    public CompletableFuture<Integer> serverVersion() {
        // 服务端响应形如 {"version": 2}；反序列化为 Map 后取 "version" 字段。
        return transport.get("version", Map.of(), Map.of(),
                        new TypeReference<Map<String, Integer>>() {})
                .thenApply(body -> {
                    Integer v = body == null ? null : body.get("version");
                    if (v == null) {
                        throw new IllegalStateException(
                                "GET /version response missing 'version' field: " + body);
                    }
                    return v;
                });
    }

    @Override
    public CompletableFuture<MidpointResponse> getMidpoint(String tokenId) {
        Objects.requireNonNull(tokenId, "tokenId");
        return transport.get("midpoint", Map.of("token_id", tokenId), Map.of(),
                new TypeReference<MidpointResponse>() {});
    }

    @Override
    public CompletableFuture<PriceResponse> getPrice(String tokenId, Side side) {
        Objects.requireNonNull(tokenId, "tokenId");
        Objects.requireNonNull(side, "side");
        // 使用 LinkedHashMap 保证 query 参数顺序可复现，利于上游 CDN 缓存命中
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("token_id", tokenId);
        params.put("side", side.toQueryValue());
        return transport.get("price", params, Map.of(), new TypeReference<PriceResponse>() {});
    }

    @Override
    public CompletableFuture<OrderBookSnapshot> getOrderBook(String tokenId) {
        Objects.requireNonNull(tokenId, "tokenId");
        return transport.get("book", Map.of("token_id", tokenId), Map.of(),
                new TypeReference<OrderBookSnapshot>() {});
    }

    @Override
    public CompletableFuture<MarketResponse> getMarket(String conditionId) {
        Objects.requireNonNull(conditionId, "conditionId");
        // 对 conditionId 做 path segment 编码，避免特殊字符（空格、/、#、? 等）破坏 URI
        return transport.get("markets/" + HttpTransport.pathSegment(conditionId),
                Map.of(), Map.of(), new TypeReference<MarketResponse>() {});
    }

    @Override
    public CompletableFuture<TickSize> getTickSize(String tokenId) {
        Objects.requireNonNull(tokenId, "tokenId");
        return transport.get("tick-size", Map.of("token_id", tokenId), Map.of(),
                        new TypeReference<TickSizeResponse>() {})
                .thenApply(TickSizeResponse::minimumTickSize);
    }

    @Override
    public CompletableFuture<Boolean> getNegRisk(String tokenId) {
        Objects.requireNonNull(tokenId, "tokenId");
        return transport.get("neg-risk", Map.of("token_id", tokenId), Map.of(),
                        new TypeReference<NegRiskResponse>() {})
                .thenApply(NegRiskResponse::negRisk);
    }

    @Override
    public CompletableFuture<Integer> getFeeRateBps(String tokenId) {
        Objects.requireNonNull(tokenId, "tokenId");
        return transport.get("fee-rate", Map.of("token_id", tokenId), Map.of(),
                        new TypeReference<FeeRateResponse>() {})
                .thenApply(FeeRateResponse::baseFee);
    }
}
