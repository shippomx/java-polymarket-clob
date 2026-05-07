package com.polymarket.clob.deposit;

import com.polymarket.clob.model.Address;

import java.math.BigInteger;

/** Deposit Wallet Batch 中单条 call。{@code data} 是 ABI 编码的 calldata。 */
public record Call(Address target, BigInteger value, byte[] data) {
    public Call {
        if (value == null) value = BigInteger.ZERO;
        if (data == null) data = new byte[0];
    }
}
