package com.polymarket.clob.auth;

import com.polymarket.clob.exception.ClobSignatureException;
import com.polymarket.clob.model.Address;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Unsigned ClobAuth payload for external (BE → App) signing flows.
 *
 * <p>Hold both the 32-byte signing digest (authoritative) and the EIP-712 typed-data JSON
 * (so the App can drive {@code eth_signTypedData_v4}). The two values must round-trip:
 * {@code keccak256(eip712Encode(typedDataJson)) == signingDigest32}.</p>
 */
public record UnsignedClobAuth(
        Address    address,
        long       chainId,
        long       timestamp,
        BigInteger nonce,
        byte[]     signingDigest32,
        String     typedDataJson) {

    public static UnsignedClobAuth buildUnsigned(Address eoa, long chainId, long timestamp, BigInteger nonce) {
        Objects.requireNonNull(eoa, "eoa");
        BigInteger n = nonce == null ? BigInteger.ZERO : nonce;
        ClobAuth auth = ClobAuth.of(eoa, timestamp, n);
        byte[] digest = Eip712TypedData.hashClobAuth(auth, chainId);
        String json   = Eip712TypedData.typedDataJsonClobAuth(auth, chainId);
        return new UnsignedClobAuth(eoa, chainId, timestamp, n, digest, json);
    }

    public static Map<String, String> attachSignature(UnsignedClobAuth unsigned, byte[] sig65) {
        Objects.requireNonNull(unsigned, "unsigned");
        if (sig65 == null || sig65.length != 65) {
            throw new ClobSignatureException(
                    "ClobAuth signature must be 65 bytes, got " + (sig65 == null ? -1 : sig65.length));
        }
        String signatureHex = "0x" + HexFormat.of().formatHex(sig65);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(L1HeaderBuilder.POLY_ADDRESS, unsigned.address.toLowerHex());
        headers.put(L1HeaderBuilder.POLY_NONCE, unsigned.nonce.toString());
        headers.put(L1HeaderBuilder.POLY_SIGNATURE, signatureHex);
        headers.put(L1HeaderBuilder.POLY_TIMESTAMP, Long.toString(unsigned.timestamp));
        return headers;
    }
}
