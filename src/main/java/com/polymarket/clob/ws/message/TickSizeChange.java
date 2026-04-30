package com.polymarket.clob.ws.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.polymarket.clob.model.Hash32;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Tick size 变化消息（{@code event_type="tick_size_change"}），对应 Rust
 * {@code TickSizeChange}。
 *
 * <p>触发时机：当某个 asset 的价格区间跨过临界值（如 5c / 1c），后端切换最小报价
 * 增量；客户端要重新做价格四舍五入与下单约束。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TickSizeChange(
        @JsonProperty("asset_id") BigInteger assetId,
        @JsonProperty("market") Hash32 market,
        @JsonProperty("old_tick_size") BigDecimal oldTickSize,
        @JsonProperty("new_tick_size") BigDecimal newTickSize,
        @JsonProperty("timestamp") long timestamp) implements WsMessage {
}
