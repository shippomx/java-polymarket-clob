package com.polymarket.clob.signing;

import com.polymarket.clob.auth.UnsignedClobAuth;
import com.polymarket.clob.deposit.Call;
import com.polymarket.clob.deposit.SignedBatch;
import com.polymarket.clob.deposit.UnsignedBatch;
import com.polymarket.clob.gamma.SignedSiwe;
import com.polymarket.clob.gamma.UnsignedSiwe;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.order.OrderV2;
import com.polymarket.clob.order.SignedOrderV2;
import com.polymarket.clob.order.UnsignedOrderV2Pol1271;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Aggregating facade for the four external-signing payload classes.
 *
 * <p>This class exists for documentation discoverability — every method is a one-line forward to
 * {@code UnsignedXxx.buildUnsigned(...)} or {@code UnsignedXxx.attachSignature(...)} in the
 * payload's own package.</p>
 */
public final class ExternalSigning {

    private ExternalSigning() {}

    // -- ClobAuth (L1 derive headers) -----------------------------------------------------

    public static UnsignedClobAuth buildUnsignedClobAuth(Address eoa, long chainId, long timestamp, BigInteger nonce) {
        return UnsignedClobAuth.buildUnsigned(eoa, chainId, timestamp, nonce);
    }

    public static Map<String, String> attachClobAuthSignature(UnsignedClobAuth unsigned, byte[] sig65) {
        return UnsignedClobAuth.attachSignature(unsigned, sig65);
    }

    // -- DepositWallet Batch --------------------------------------------------------------

    public static UnsignedBatch buildUnsignedBatch(long chainId, Address eoa, Address wallet,
                                                    BigInteger nonce, BigInteger deadline,
                                                    List<Call> calls) {
        return UnsignedBatch.buildUnsigned(chainId, eoa, wallet, nonce, deadline, calls);
    }

    public static SignedBatch attachBatchSignature(UnsignedBatch unsigned, byte[] sig65) {
        return UnsignedBatch.attachSignature(unsigned, sig65);
    }

    // -- SIWE -----------------------------------------------------------------------------

    public static UnsignedSiwe buildUnsignedSiwe(Address eoa, long chainId, String nonce,
                                                  Instant issuedAt, Instant expirationTime) {
        return UnsignedSiwe.buildUnsigned(eoa, chainId, nonce, issuedAt, expirationTime);
    }

    public static SignedSiwe attachSiweSignature(UnsignedSiwe unsigned, byte[] sig65) {
        return UnsignedSiwe.attachSignature(unsigned, sig65);
    }

    // -- Order V2 POLY_1271 (ERC-7739 nested) ---------------------------------------------

    public static UnsignedOrderV2Pol1271 buildUnsignedOrderV2Pol1271(OrderV2 order, long chainId, boolean negRisk) {
        return UnsignedOrderV2Pol1271.buildUnsigned(order, chainId, negRisk);
    }

    public static SignedOrderV2 attachOrderV2Pol1271Signature(UnsignedOrderV2Pol1271 unsigned, byte[] innerSig65) {
        return UnsignedOrderV2Pol1271.attachSignature(unsigned, innerSig65);
    }
}
