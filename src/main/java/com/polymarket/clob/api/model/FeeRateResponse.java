package com.polymarket.clob.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code GET /fee-rate} 响应。
 *
 * <p>上游形态：{@code {"base_fee": <bps>}}，单位 basis points（1 bp = 0.01%）。
 * 例如 {@code base_fee=10} 即 0.10%；订单签名时把这个值放进 {@code feeRateBps}，
 * 与 {@link com.polymarket.clob.order.LimitOrderArgs#getFeeRateBps()} 对应。</p>
 *
 * <p>对齐 Rust {@code FeeRateResponse}（{@code rs-clob-client}）；首版仅暴露 {@code base_fee}
 * 单字段，未来若服务端加 {@code maker_fee/taker_fee} 拆分再扩展。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FeeRateResponse(@JsonProperty("base_fee") int baseFee) {
}
