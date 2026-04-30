package com.polymarket.clob.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.model.Address;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.math.BigInteger;

/**
 * Polymarket CTF Exchange 订单 wire 模型。13 字段来自链上 {@code Order} struct（EIP-712 签名
 * 基础），对齐 python-order-utils 的 {@code Order.dict()} 输出与 CLOB REST body。
 *
 * <p>序列化差异与关键约定：
 * <ul>
 *   <li>{@code salt} → JSON 数字（保留 {@link BigInteger} 精度）；{@code signatureType}
 *       → JSON 数字（{@link SignatureType#code()}）。</li>
 *   <li>{@code tokenId / makerAmount / takerAmount / expiration / nonce / feeRateBps}
 *       → JSON 字符串（上游要求的 uint256 字符串形态），靠
 *       {@link ToStringSerializer} 出字面值，反序列化复用 Jackson 内置逻辑（同时接受
 *       字符串与数字）。</li>
 *   <li>{@code side} → {@code "BUY"} / {@code "SELL"}（{@link Side} 默认按 {@code name()}
 *       序列化，与上游保持一致）。</li>
 *   <li>{@code maker / signer / taker} → 小写十六进制地址（{@link Address#toHex()}），
 *       反序列化由 {@link Address} 的 {@code @JsonCreator} 承接，不区分大小写。</li>
 * </ul>
 *
 * <p>EIP-712 签名阶段则需要纯数值：见 {@code EIP712OrderSigner}，从本类按字段提取构造 struct
 * hash。</p>
 *
 * <p>构造通常走 {@link Order#builder()}；{@link Jacksonized} 让 Jackson 同样走 builder
 * 路径，便于未来字段增减。</p>
 */
@Value
@Builder(toBuilder = true)
@Jacksonized
public class Order {

    @JsonProperty("salt")
    BigInteger salt;

    @JsonProperty("maker")
    Address maker;

    @JsonProperty("signer")
    Address signer;

    @JsonProperty("taker")
    Address taker;

    @JsonProperty("tokenId")
    @JsonSerialize(using = ToStringSerializer.class)
    BigInteger tokenId;

    @JsonProperty("makerAmount")
    @JsonSerialize(using = ToStringSerializer.class)
    BigInteger makerAmount;

    @JsonProperty("takerAmount")
    @JsonSerialize(using = ToStringSerializer.class)
    BigInteger takerAmount;

    @JsonProperty("expiration")
    @JsonSerialize(using = ToStringSerializer.class)
    BigInteger expiration;

    @JsonProperty("nonce")
    @JsonSerialize(using = ToStringSerializer.class)
    BigInteger nonce;

    @JsonProperty("feeRateBps")
    @JsonSerialize(using = ToStringSerializer.class)
    BigInteger feeRateBps;

    @JsonProperty("side")
    Side side;

    @JsonProperty("signatureType")
    SignatureType signatureType;
}
