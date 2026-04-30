package com.polymarket.clob.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.math.BigDecimal;

/**
 * 订单簿中单个档位：价格 + 数量。对应 Rust {@code OrderSummary}。
 */
@Value
@Builder
@Jacksonized
public class OrderSummary {
    @JsonProperty("price") BigDecimal price;
    @JsonProperty("size") BigDecimal size;
}
