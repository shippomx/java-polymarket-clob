package com.polymarket.clob.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.math.BigDecimal;
import java.util.List;

/**
 * 对应 Rust {@code MarketResponse} 的常用子集。
 *
 * <p>Plan 2 前置清单补齐 {@code tokens} / {@code negRisk} / {@code question}
 * / {@code endDateIso} / {@code tags}：这些是 Plan 2+ 路由 / 下单 / UI 展示会直接读到的字段。
 * {@code rewards} / {@code game_start_time} / {@code fpmm} 等低频字段留到后续 Plan 按需补。</p>
 */
@Value
@Builder
@Jacksonized
public class MarketResponse {
    @JsonProperty("enable_order_book") Boolean enableOrderBook;
    @JsonProperty("active") Boolean active;
    @JsonProperty("closed") Boolean closed;
    @JsonProperty("archived") Boolean archived;
    @JsonProperty("accepting_orders") Boolean acceptingOrders;
    @JsonProperty("minimum_order_size") BigDecimal minimumOrderSize;
    @JsonProperty("minimum_tick_size") BigDecimal minimumTickSize;
    @JsonProperty("condition_id") String conditionId;
    @JsonProperty("question_id") String questionId;
    @JsonProperty("question") String question;
    @JsonProperty("description") String description;
    @JsonProperty("market_slug") String marketSlug;
    @JsonProperty("end_date_iso") String endDateIso;
    @JsonProperty("neg_risk") Boolean negRisk;
    @JsonProperty("tokens") List<Token> tokens;
    @JsonProperty("tags") List<String> tags;
}
