package com.polymarket.clob.order;

import com.polymarket.clob.api.model.Side;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * V2 限价单参数。对齐 py-clob-client-v2 {@code OrderArgsV2}。
 *
 * <p>与 V1 {@link LimitOrderArgs} 的区别（V2 服务端不再接受 V1 字段）：
 * <ul>
 *   <li><b>移除</b>：{@code feeRateBps / nonce / taker}（这些字段已退出签名 struct，wire 上也不再带）。</li>
 *   <li><b>新增</b>：
 *     <ul>
 *       <li>{@link #builderCode}：bytes32 hex（含 {@code 0x}），用于 builder 费率归属，默认全零；</li>
 *       <li>{@link #metadata}：bytes32 hex，调用方自定义元数据，默认全零。</li>
 *     </ul>
 *   </li>
 *   <li>{@link #expiration}：保留为 wire 字段，但 V2 EIP-712 签名不会摄入此字段
 *       （asymmetry 见 {@link OrderV2}）。</li>
 * </ul>
 * </p>
 *
 * <p>所有 bytes32 默认值 {@link #BYTES32_ZERO} 与 py-clob-client-v2 {@code BYTES32_ZERO} 一致。</p>
 */
@Value
@Builder(toBuilder = true)
public class LimitOrderArgsV2 {

    /** 全 0 的 bytes32 默认值（{@code 0x0000...0000}），共 66 字符。 */
    public static final String BYTES32_ZERO = "0x" + "00".repeat(32);

    BigInteger tokenId;
    BigDecimal price;
    BigDecimal size;
    Side side;

    /** Unix 秒；{@code 0} 表示不过期（wire 上仍输出，但不入签名 struct）。 */
    @Builder.Default
    long expiration = 0L;

    /** 0x-prefixed 32-byte hex；默认全零。 */
    @Builder.Default
    String builderCode = BYTES32_ZERO;

    /** 0x-prefixed 32-byte hex；默认全零。 */
    @Builder.Default
    String metadata = BYTES32_ZERO;
}
