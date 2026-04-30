package com.polymarket.clob.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.auth.L2HeaderBuilder;
import com.polymarket.clob.http.CursorPager;
import com.polymarket.clob.http.HttpTransport;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.trade.BuilderTrade;
import com.polymarket.clob.trade.Trade;
import com.polymarket.clob.trade.TradesRequest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * {@link TradeApi} 的默认实现。{@code /data/trades} 与 {@code /builder/trades}
 * 走同一套游标分页，只是后者需要叠加 Builder 头。
 */
public final class TradeApiImpl implements TradeApi {

    private static final String DATA_TRADES_PATH = "/data/trades";
    private static final String BUILDER_TRADES_PATH = "/builder/trades";

    private final HttpTransport transport;

    public TradeApiImpl(HttpTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public Stream<Trade> getTrades(Address caller,
                                   ApiCredentials credentials,
                                   long timestamp,
                                   TradesRequest request) {
        Map<String, String> headers = L2HeaderBuilder.build(
                caller, credentials, "GET", DATA_TRADES_PATH, "", timestamp);
        Map<String, String> baseQuery = request == null
                ? Map.of() : request.toQueryParams();
        return CursorPager.stream(
                transport,
                "data/trades",
                baseQuery,
                headers,
                new TypeReference<CursorPager.Page<Trade>>() {});
    }

    @Override
    public Stream<BuilderTrade> getBuilderTrades(Address caller,
                                                 ApiCredentials credentials,
                                                 long timestamp,
                                                 TradesRequest request,
                                                 Map<String, String> extraHeaders) {
        Map<String, String> headers = L2HeaderBuilder.build(
                caller, credentials, "GET", BUILDER_TRADES_PATH, "", timestamp);
        if (extraHeaders != null && !extraHeaders.isEmpty()) {
            // 注意：LinkedHashMap 保留 L2 在前 / builder 在后，方便排错时直接对照 wire
            Map<String, String> merged = new LinkedHashMap<>(headers);
            merged.putAll(extraHeaders);
            headers = merged;
        }
        Map<String, String> baseQuery = request == null
                ? Map.of() : request.toQueryParams();
        return CursorPager.stream(
                transport,
                "builder/trades",
                baseQuery,
                headers,
                new TypeReference<CursorPager.Page<BuilderTrade>>() {});
    }
}
