package com.polymarket.clob.ws.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.model.Hash32;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * 最近成交价消息（{@code event_type="last_trade_price"}），对应 Rust {@code LastTradePrice}。
 *
 * <p>{@code side / size / fee_rate_bps} 在某些撮合路径上服务端不一定回填，记得
 * 容忍 null。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LastTradePrice(
        @JsonProperty("asset_id") BigInteger assetId,
        @JsonProperty("market") Hash32 market,
        @JsonProperty("price") BigDecimal price,
        @JsonProperty("side") Side side,
        @JsonProperty("size") BigDecimal size,
        @JsonProperty("fee_rate_bps") BigDecimal feeRateBps,
        @JsonProperty("timestamp") long timestamp) implements WsMessage {
}
