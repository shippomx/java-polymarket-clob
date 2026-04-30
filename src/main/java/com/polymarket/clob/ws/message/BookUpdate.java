package com.polymarket.clob.ws.message;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.polymarket.clob.model.Hash32;

import java.math.BigInteger;
import java.util.List;

/**
 * 订单簿快照 / 增量更新消息（{@code event_type="book"}），对齐 Rust {@code BookUpdate}。
 *
 * <p>触发时机：首次订阅、撮合或撤单引起最优档变动时。
 * 服务端**返回的是当前完整 book 状态**（snapshot 语义），调用方无需自行重建。</p>
 *
 * <p>{@link #timestamp} 在 wire 上是字符串毫秒时间戳，已在反序列化时通过
 * Jackson 标量 coercion 转 {@code long}。</p>
 *
 * @see OrderBookLevel
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookUpdate(
        @JsonProperty("asset_id") BigInteger assetId,
        @JsonProperty("market") Hash32 market,
        @JsonProperty("timestamp") long timestamp,
        @JsonAlias({"bids"}) @JsonProperty("bids") List<OrderBookLevel> bids,
        @JsonAlias({"asks"}) @JsonProperty("asks") List<OrderBookLevel> asks,
        @JsonProperty("hash") String hash) implements WsMessage {

    public BookUpdate {
        bids = bids == null ? List.of() : List.copyOf(bids);
        asks = asks == null ? List.of() : List.copyOf(asks);
    }
}
