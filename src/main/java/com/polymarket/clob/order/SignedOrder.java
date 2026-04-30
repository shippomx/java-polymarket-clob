package com.polymarket.clob.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.Builder;
import lombok.Value;

import java.util.Objects;

/**
 * 签名后的订单：{@link Order} 12 字段 + 65 字节十六进制签名；JSON 序列化时用
 * {@link JsonUnwrapped} 把 Order 字段平铺，最终线上 wire 形如：
 *
 * <pre>
 * {
 *   "salt": 123,
 *   "maker": "0x...",
 *   ...
 *   "signatureType": 0,
 *   "signature": "0xabc...(130 hex)..."
 * }
 * </pre>
 *
 * <p>反序列化通常不用本类（post-order 响应是另一套结构），所以只配置序列化路径。</p>
 */
@Value
@Builder(toBuilder = true)
public class SignedOrder {

    /**
     * 内嵌订单结构。{@link JsonUnwrapped} 仅负责序列化打平；反序列化如需支持，
     * 未来补 {@code @JsonCreator} 工厂即可。
     */
    @JsonUnwrapped
    Order order;

    /** 65 字节 {@code r||s||v} 签名，格式 {@code 0x} + 130 位十六进制。 */
    @JsonProperty("signature")
    String signature;

    /** 语义化工厂：签完名后合成 {@link SignedOrder}，保证字段顺序稳定。 */
    public static SignedOrder of(Order order, String signature) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(signature, "signature");
        if (!signature.startsWith("0x") || signature.length() != 132) {
            throw new IllegalArgumentException(
                    "signature must be 0x-prefixed 65-byte hex (132 chars), got length " + signature.length());
        }
        return new SignedOrder(order, signature);
    }
}
