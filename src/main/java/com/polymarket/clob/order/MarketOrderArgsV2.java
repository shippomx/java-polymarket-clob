package com.polymarket.clob.order;

import com.polymarket.clob.api.model.Side;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * V2 市价单参数。对齐 py-clob-client-v2 {@code MarketOrderArgsV2}。
 *
 * <p>与 V1 {@link MarketOrderArgs} 的区别同 {@link LimitOrderArgsV2}：移除
 * {@code feeRateBps / nonce / taker}，新增 {@code builderCode / metadata}；
 * V2 市价单 expiration 强制为 0（不暴露字段，与 V1 一致）。</p>
 */
@Value
@Builder(toBuilder = true)
public class MarketOrderArgsV2 {

    BigInteger tokenId;

    /** BUY: 想花的 USDC 数量；SELL: 想卖的份额数量。 */
    BigDecimal amount;

    /** 滑点保护：BUY 是上限价，SELL 是下限价；必须 &gt; 0。 */
    BigDecimal price;

    Side side;

    @Builder.Default
    String builderCode = LimitOrderArgsV2.BYTES32_ZERO;

    @Builder.Default
    String metadata = LimitOrderArgsV2.BYTES32_ZERO;
}
