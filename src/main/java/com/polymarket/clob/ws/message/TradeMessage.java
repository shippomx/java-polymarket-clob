package com.polymarket.clob.ws.message;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.api.model.TraderSide;
import com.polymarket.clob.model.Hash32;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

/**
 * 用户态 trade 推送（{@code event_type="trade"}），仅 user channel 出现，对应
 * Rust {@code TradeMessage}。
 *
 * <p>{@code matchTime} 在不同版本服务端有 {@code matchtime} / {@code match_time}
 * 两种字段名，用 {@link JsonAlias} 一并兼容。</p>
 *
 * <p>{@code status} 用字符串保留：服务端枚举值 {@code MATCHED / MINED /
 * CONFIRMED / RETRYING / FAILED} 大小写不固定（实测有混用），不强行套 enum。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TradeMessage(
        @JsonProperty("id") String id,
        @JsonProperty("market") Hash32 market,
        @JsonProperty("asset_id") BigInteger assetId,
        @JsonProperty("side") Side side,
        @JsonProperty("size") BigDecimal size,
        @JsonProperty("price") BigDecimal price,
        @JsonProperty("status") String status,
        @JsonProperty("type") String messageType,
        @JsonProperty("last_update") Long lastUpdate,
        @JsonAlias({"matchtime", "match_time"}) @JsonProperty("matchtime") Long matchTime,
        @JsonProperty("timestamp") Long timestamp,
        @JsonProperty("outcome") String outcome,
        @JsonProperty("owner") String owner,
        @JsonProperty("trade_owner") String tradeOwner,
        @JsonProperty("taker_order_id") String takerOrderId,
        @JsonProperty("maker_orders") List<MakerOrder> makerOrders,
        @JsonProperty("fee_rate_bps") BigDecimal feeRateBps,
        @JsonProperty("transaction_hash") Hash32 transactionHash,
        @JsonProperty("trader_side") TraderSide traderSide) implements WsMessage {

    public TradeMessage {
        makerOrders = makerOrders == null ? List.of() : List.copyOf(makerOrders);
    }
}
