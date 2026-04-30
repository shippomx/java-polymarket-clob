package com.polymarket.clob.ws.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * 订单簿单层（{@code price / size}），对应 Rust {@code OrderBookLevel}。
 *
 * <p>字段 wire 形态：JSON 数字字符串（{@code "0.5"}），由 Jackson 自动转 {@link BigDecimal}。</p>
 */
public record OrderBookLevel(
        @JsonProperty("price") BigDecimal price,
        @JsonProperty("size") BigDecimal size) {

    @JsonCreator
    public OrderBookLevel {
        // record canonical constructor 已自动 @JsonCreator
    }
}
