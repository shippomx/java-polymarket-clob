package com.polymarket.clob.ws.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.model.Hash32;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

/**
 * 用户态订单更新推送（{@code event_type="order"}），仅 user channel 出现，对应
 * Rust {@code OrderMessage}。
 *
 * <p>{@code messageType} 取值：{@code PLACEMENT / UPDATE / CANCELLATION}，
 * {@code status} 取值：{@code LIVE / MATCHED / CANCELED / DELAYED} 等，
 * 上游存在大小写混用，统一用 String 容错。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderMessage(
        @JsonProperty("id") String id,
        @JsonProperty("market") Hash32 market,
        @JsonProperty("asset_id") BigInteger assetId,
        @JsonProperty("side") Side side,
        @JsonProperty("price") BigDecimal price,
        @JsonProperty("type") String messageType,
        @JsonProperty("outcome") String outcome,
        @JsonProperty("owner") String owner,
        @JsonProperty("order_owner") String orderOwner,
        @JsonProperty("original_size") BigDecimal originalSize,
        @JsonProperty("size_matched") BigDecimal sizeMatched,
        @JsonProperty("timestamp") Long timestamp,
        @JsonProperty("associate_trades") List<String> associateTrades,
        @JsonProperty("status") String status) implements WsMessage {

    public OrderMessage {
        associateTrades = associateTrades == null ? List.of() : List.copyOf(associateTrades);
    }
}
