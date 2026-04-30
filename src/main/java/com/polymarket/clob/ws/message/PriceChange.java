package com.polymarket.clob.ws.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.polymarket.clob.model.Hash32;

import java.util.List;

/**
 * 价格变更消息（{@code event_type="price_change"}），对应 Rust {@code PriceChange}。
 *
 * <p>包含同一 market 下若干 tick 的价格调整；调用方按需要遍历
 * {@link #priceChanges()} 处理。</p>
 *
 * @see PriceChangeBatchEntry
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PriceChange(
        @JsonProperty("market") Hash32 market,
        @JsonProperty("timestamp") long timestamp,
        @JsonProperty("price_changes") List<PriceChangeBatchEntry> priceChanges) implements WsMessage {

    public PriceChange {
        priceChanges = priceChanges == null ? List.of() : List.copyOf(priceChanges);
    }
}
