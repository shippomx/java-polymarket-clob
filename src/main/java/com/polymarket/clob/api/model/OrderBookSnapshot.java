package com.polymarket.clob.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.math.BigDecimal;
import java.util.List;

/**
 * 对应 Rust {@code OrderBookSummaryResponse}。
 *
 * <p>上游 {@code timestamp} 为毫秒级 Unix 时间戳字符串，保留为 {@link String} 以避免时区歧义；
 * 上层需要 {@code java.time.Instant} 时再自行解析。</p>
 *
 * <p>{@code minOrderSize} / {@code negRisk} / {@code tickSize} 是 Plan 3 下单前本地校验必用的字段，
 * 提前在 Plan 2 前置清单里补齐。{@code lastTradePrice} 上游可能缺省或为非数字，字段声明为可空。</p>
 */
@Value
@Builder
@Jacksonized
public class OrderBookSnapshot {
    @JsonProperty("market") String market;
    @JsonProperty("asset_id") String assetId;
    @JsonProperty("timestamp") String timestampMillis;
    @JsonProperty("hash") String hash;
    @JsonProperty("bids") List<OrderSummary> bids;
    @JsonProperty("asks") List<OrderSummary> asks;
    @JsonProperty("min_order_size") BigDecimal minOrderSize;
    @JsonProperty("neg_risk") Boolean negRisk;
    @JsonProperty("tick_size") BigDecimal tickSize;
    @JsonProperty("last_trade_price") BigDecimal lastTradePrice;
}
