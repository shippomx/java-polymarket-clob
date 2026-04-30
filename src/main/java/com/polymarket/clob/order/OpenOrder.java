package com.polymarket.clob.order;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.model.Address;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * {@code GET /data/order/{id}} / {@code GET /data/orders} 返回的订单快照。
 *
 * <p>字段依据 py-clob-client 观察到的 payload；数值字段（price/size）使用
 * {@link BigDecimal} 保持精度，份额/fee 使用 {@link BigInteger}/{@code long}。未知字段忽略。</p>
 *
 * <p>故意只映射核心字段——后续如需 {@code associate_trades}、{@code fee_rate_bps} 等再按需扩展。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenOrder(
        @JsonProperty("id") String id,
        @JsonProperty("status") String status,
        @JsonProperty("owner") String owner,
        @JsonProperty("maker_address") Address makerAddress,
        @JsonProperty("market") String market,
        @JsonProperty("asset_id") String assetId,
        @JsonProperty("side") Side side,
        @JsonProperty("original_size") BigDecimal originalSize,
        @JsonProperty("size_matched") BigDecimal sizeMatched,
        @JsonProperty("price") BigDecimal price,
        @JsonProperty("outcome") String outcome,
        @JsonProperty("expiration") String expiration,
        @JsonProperty("order_type") OrderType orderType,
        @JsonProperty("created_at") long createdAt) {
}
