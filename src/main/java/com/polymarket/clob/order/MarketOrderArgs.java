package com.polymarket.clob.order;

import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.model.Address;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * 市价单参数。对齐 py-clob-client {@code MarketOrderArgs}：
 *
 * <ul>
 *   <li>{@link #tokenId}：CTF 资产 id。</li>
 *   <li>{@link #amount}：
 *     <ul>
 *       <li>BUY → 想花的美元数量。</li>
 *       <li>SELL → 想卖出的份额数量。</li>
 *     </ul></li>
 *   <li>{@link #price}：最差价格限制（滑点保护）；必须 &gt; 0。</li>
 *   <li>{@link #side}：{@link Side#BUY} / {@link Side#SELL}。</li>
 * </ul>
 *
 * <p>{@code expiration} 始终强制为 0（市价单立即成交），该字段不暴露。</p>
 */
@Value
@Builder(toBuilder = true)
public class MarketOrderArgs {

    BigInteger tokenId;
    BigDecimal amount;
    BigDecimal price;
    Side side;

    @Builder.Default
    int feeRateBps = 0;

    @Builder.Default
    BigInteger nonce = BigInteger.ZERO;

    @Builder.Default
    Address taker = Address.ZERO;
}
