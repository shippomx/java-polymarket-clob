package com.polymarket.clob.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.Builder;
import lombok.Value;

import java.util.Objects;

/**
 * 签名后的 v2 订单：{@link OrderV2} 11 字段（含 wire-only {@code expiration}）+ 65 字节十六进制签名。
 *
 * <p>JSON 序列化用 {@link JsonUnwrapped} 把 OrderV2 字段平铺到外层；最终 wire JSON 顺序由
 * {@link OrderV2} 字段定义：salt / maker / signer / tokenId / makerAmount / takerAmount /
 * side / expiration / signatureType / timestamp / metadata / builder / signature。
 *
 * <p>与 py-clob-client-v2 {@code order_to_json_v2} 输出顺序刻意一致，便于 wire diff 与黄金向量复刻。</p>
 *
 * <p>反序列化通常用不到本类（POST /order 响应是另一套结构），所以只配置序列化路径。</p>
 */
@Value
@Builder(toBuilder = true)
public class SignedOrderV2 {

    @JsonUnwrapped
    OrderV2 order;

    /** 65 字节 {@code r||s||v} 签名，格式 {@code 0x} + 130 位十六进制。 */
    @JsonProperty("signature")
    String signature;

    /**
     * 语义化工厂：签完名后合成 {@link SignedOrderV2}，确保签名形态正确。
     *
     * <p>签名长度约束：
     * <ul>
     *   <li>EIP-712 ECDSA（EOA / POLY_EOA）：恰好 65 字节（132 hex 字符）。</li>
     *   <li>ERC-7739 POLY_1271：65 + 32 + 32 + ORDER_TYPE_STRING_len + 2 字节，
     *       长度可变，但最少 131 字节（至少大于 65 字节）。</li>
     * </ul>
     * 工厂只检查 {@code 0x} 前缀 + 偶数长度 + ≥ 65 字节（130 hex 字符）。
     * </p>
     */
    public static SignedOrderV2 of(OrderV2 order, String signature) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(signature, "signature");
        int hexLen = signature.length();
        if (!signature.startsWith("0x") || hexLen < 132 || (hexLen % 2) != 0) {
            throw new IllegalArgumentException(
                    "signature must be 0x-prefixed even-length hex (≥ 65 bytes / 132 hex chars), got length "
                            + hexLen);
        }
        return new SignedOrderV2(order, signature);
    }
}
