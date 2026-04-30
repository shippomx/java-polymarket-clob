package com.polymarket.clob.order;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code GET /order-scoring} 的响应，对应 Rust {@code OrderScoringResponse}。
 *
 * <p>仅一个布尔字段 {@code scoring}：指示该订单当前是否满足市价马上做市商奖励资格。
 * 与 {@link OrdersScoringResponse}（批量 Map）互补。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderScoringResponse(@JsonProperty("scoring") boolean scoring) {

    @JsonCreator
    public OrderScoringResponse {
        // compact canonical form
    }
}
