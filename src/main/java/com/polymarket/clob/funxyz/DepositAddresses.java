package com.polymarket.clob.funxyz;

import com.polymarket.clob.model.Address;

/**
 * fun.xyz 给某个 EOA 在四条链上的固定入金中转地址。
 *
 * <p>同一 {@code eoa} 多次调用 fun.xyz 会得到相同的四个地址（服务端持久映射）。</p>
 *
 * <p>仅 EVM 地址使用项目自带 {@link Address} 类型；其它三条链项目无 value object，
 * 保留服务端原始字符串。调用方按需校验/转换。</p>
 *
 * @param evm        Polygon (chainId=137) 上的入金 EOA
 * @param solana     Solana base58 地址
 * @param tron       Tron base58 地址（"T..."）
 * @param btcSegwit  Bitcoin segwit 地址（"bc1q..."）
 */
public record DepositAddresses(
        Address evm,
        String solana,
        String tron,
        String btcSegwit
) {}
