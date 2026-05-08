package com.polymarket.clob.deposit;

import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.exception.ClobSignatureException;
import com.polymarket.clob.model.Address;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

public record UnsignedBatch(
        long       chainId,
        Address    eoa,
        Address    wallet,
        BigInteger nonce,
        BigInteger deadline,
        List<Call> calls,
        byte[]     signingDigest32,
        String     typedDataJson) {

    public UnsignedBatch {
        Objects.requireNonNull(eoa, "eoa");
        Objects.requireNonNull(wallet, "wallet");
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(calls, "calls");
        if (calls.isEmpty()) {
            throw new IllegalArgumentException("calls 不能为空");
        }
        calls = List.copyOf(calls);
    }

    public static UnsignedBatch buildUnsigned(long chainId, Address eoa, Address wallet,
                                               BigInteger nonce, BigInteger deadline,
                                               List<Call> calls) {
        byte[] digest = BatchEip712.hashBatch(chainId, wallet, nonce, deadline, calls);
        String json   = BatchEip712.typedDataJsonBatch(chainId, wallet, nonce, deadline, calls);
        return new UnsignedBatch(chainId, eoa, wallet, nonce, deadline, calls, digest, json);
    }

    public static SignedBatch attachSignature(UnsignedBatch unsigned, byte[] sig65) {
        Objects.requireNonNull(unsigned, "unsigned");
        if (sig65 == null || sig65.length != 65) {
            throw new ClobSignatureException(
                    "Batch signature must be 65 bytes, got " + (sig65 == null ? -1 : sig65.length));
        }
        return new SignedBatch(unsigned.eoa, PolymarketContracts.FACTORY, unsigned.wallet,
                unsigned.nonce, unsigned.deadline, unsigned.calls, sig65.clone());
    }
}
