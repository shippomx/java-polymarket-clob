package com.polymarket.clob.deposit;

import com.polymarket.clob.model.Address;

import java.math.BigInteger;
import java.util.List;

/** 已签名的 Batch：{@code signature65} 是 EOA 对 BatchEip712 摘要的 65 字节 ECDSA 签名。 */
public record SignedBatch(
        Address eoa,
        Address factory,
        Address wallet,
        BigInteger nonce,
        BigInteger deadline,
        List<Call> calls,
        byte[] signature65
) {}
