package com.polymarket.clob.order;

import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.model.Address;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * 限价单参数。对齐 py-clob-client {@code OrderArgs}：
 *
 * <ul>
 *   <li>{@link #tokenId}：CTF ERC-1155 资产 id。</li>
 *   <li>{@link #price}：{@link BigDecimal}，需满足市场 tick；最终舍入由 {@link OrderRounding} 处理。</li>
 *   <li>{@link #size}：份额数量（shares，6 位小数）。</li>
 *   <li>{@link #side}：{@link Side#BUY} / {@link Side#SELL}。</li>
 * </ul>
 *
 * <p>可选字段使用 Lombok {@code @Builder.Default} 给出与 Python 相同的缺省值：
 * {@code feeRateBps=0} / {@code nonce=0} / {@code expiration=0} / {@code taker=0x000..0}。</p>
 */
@Value
@Builder(toBuilder = true)
public class LimitOrderArgs {

    BigInteger tokenId;
    BigDecimal price;
    BigDecimal size;
    Side side;

    @Builder.Default
    int feeRateBps = 0;

    @Builder.Default
    BigInteger nonce = BigInteger.ZERO;

    /** Unix 秒；{@code 0} 表示不过期。 */
    @Builder.Default
    long expiration = 0L;

    @Builder.Default
    Address taker = Address.ZERO;
}
