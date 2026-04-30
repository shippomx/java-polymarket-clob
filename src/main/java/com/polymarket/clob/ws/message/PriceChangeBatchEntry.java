package com.polymarket.clob.ws.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.polymarket.clob.api.model.Side;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * 单条价格变更项（包含在 {@link PriceChange#priceChanges()} 数组里），对应 Rust
 * {@code PriceChangeBatchEntry}。
 *
 * <p>{@code best_bid / best_ask / size / hash} 都是 optional——是否携带由服务端在
 * 批量推送时合并相邻 tick 的策略决定，调用方需要 null 兼容。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PriceChangeBatchEntry(
        @JsonProperty("asset_id") BigInteger assetId,
        @JsonProperty("price") BigDecimal price,
        @JsonProperty("size") BigDecimal size,
        @JsonProperty("side") Side side,
        @JsonProperty("hash") String hash,
        @JsonProperty("best_bid") BigDecimal bestBid,
        @JsonProperty("best_ask") BigDecimal bestAsk) {
}
