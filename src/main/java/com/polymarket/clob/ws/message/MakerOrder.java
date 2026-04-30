package com.polymarket.clob.ws.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * trade 消息中嵌套的 maker 订单条目，对应 Rust {@code MakerOrder}。
 *
 * <p>{@code owner} 是该 maker 订单所属 API key（UUID 字符串）；用于和你自己的
 * trade 区分（{@code TradeMessage#tradeOwner} ≠ owner 时表示对手成交）。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MakerOrder(
        @JsonProperty("asset_id") BigInteger assetId,
        @JsonProperty("matched_amount") BigDecimal matchedAmount,
        @JsonProperty("order_id") String orderId,
        @JsonProperty("outcome") String outcome,
        @JsonProperty("owner") String owner,
        @JsonProperty("price") BigDecimal price) {
}
