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
 * Polymarket CTF Exchange v2 订单 wire 模型。
 *
 * <p><b>V1 → V2 字段变化</b>（来源：py-clob-client-v2 / rs-clob-client-v2）：
 * <ul>
 *   <li>删除：{@code taker} / {@code expiration（签名内）} / {@code nonce} / {@code feeRateBps}；</li>
 *   <li>新增：{@code timestamp（uint256，毫秒）} / {@code metadata（bytes32）} / {@code builder（bytes32）}。</li>
 * </ul>
 * </p>
 *
 * <p><b>asymmetry 注意</b>：{@link #expiration} 仍出现在 wire JSON 上（POST /order body），
 * 但<b>不进 EIP-712 签名 struct</b>。所以本类持有 {@code expiration} 仅是为了 wire 序列化方便；
 * v2 EIP-712 摘要计算时 {@link com.polymarket.clob.auth.Eip712TypedData} 会刻意忽略它。</p>
 *
 * <p><b>序列化约定</b>（与 V1 {@link Order} 同构，便于 BE 串接）：
 * <ul>
 *   <li>{@code salt} → JSON 数字（保留 {@link BigInteger} 精度）；
 *       {@code signatureType} → JSON 数字（{@link SignatureType#code()}）。</li>
 *   <li>{@code tokenId / makerAmount / takerAmount / timestamp / expiration}
 *       → JSON 字符串（uint256 字符串形态），由 {@link ToStringSerializer} 实现。</li>
 *   <li>{@code side} → {@code "BUY"} / {@code "SELL"}（{@link Side} 默认按 {@code name()}
 *       序列化）。</li>
 *   <li>{@code maker / signer} → 小写十六进制（{@link Address#toHex()}）。</li>
 *   <li>{@code metadata / builder} → bytes32 hex（含 {@code 0x} 前缀，固定 66 字符），
 *       直接用 {@link String} 持有以避免 BigInteger 前导零截断。</li>
 * </ul>
 * </p>
 *
 * <p>EIP-712 签名见 {@link com.polymarket.clob.auth.Eip712TypedData#hashOrderV2}；
 * 限价/市价构造见 {@link OrderBuilder#createOrderV2} / {@link OrderBuilder#createMarketOrderV2}。</p>
 */
@Value
@Builder(toBuilder = true)
@Jacksonized
public class OrderV2 {

    @JsonProperty("salt")
    BigInteger salt;

    @JsonProperty("maker")
    Address maker;

    @JsonProperty("signer")
    Address signer;

    @JsonProperty("tokenId")
    @JsonSerialize(using = ToStringSerializer.class)
    BigInteger tokenId;

    @JsonProperty("makerAmount")
    @JsonSerialize(using = ToStringSerializer.class)
    BigInteger makerAmount;

    @JsonProperty("takerAmount")
    @JsonSerialize(using = ToStringSerializer.class)
    BigInteger takerAmount;

    @JsonProperty("side")
    Side side;

    /**
     * Wire 字段（不入签名 struct）。{@code 0} 表示无过期；非零为 unix 秒时间戳。
     * 与 py-clob-client-v2 {@code order_to_json_v2} 行为一致：始终输出，即使为 0。
     */
    @JsonProperty("expiration")
    @JsonSerialize(using = ToStringSerializer.class)
    BigInteger expiration;

    @JsonProperty("signatureType")
    SignatureType signatureType;

    /**
     * Unix 毫秒时间戳（uint256 字符串）。每次签名时由调用方刻入，与 salt 共同充当订单
     * 的 nonce 角色。
     */
    @JsonProperty("timestamp")
    @JsonSerialize(using = ToStringSerializer.class)
    BigInteger timestamp;

    /**
     * Bytes32 元数据，调用方自定义。默认值
     * {@code 0x0000000000000000000000000000000000000000000000000000000000000000}。
     * 必须以 {@code 0x} 开头、共 66 字符。
     */
    @JsonProperty("metadata")
    String metadata;

    /**
     * Bytes32 builder code，用于 builder 费率归属。默认值同上。
     */
    @JsonProperty("builder")
    String builder;
}
