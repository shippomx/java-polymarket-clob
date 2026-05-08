package com.polymarket.clob.gamma;

import com.polymarket.clob.exception.ClobSignatureException;
import com.polymarket.clob.model.Address;

import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Unsigned Sign-In-with-Ethereum (EIP-191 personal_sign) payload for external signing flows.
 *
 * <p>Note: SIWE is EIP-191, not EIP-712. There is no {@code typedDataJson} field — apps must
 * sign the raw 32-byte digest (or, equivalently, prepend "\x19Ethereum Signed Message:\n{len}"
 * themselves and keccak-256 the result).</p>
 */
public record UnsignedSiwe(
        Address eoa,
        long    chainId,
        String  nonce,
        Instant issuedAt,
        Instant expirationTime,
        String  canonicalMessage,
        byte[]  signingDigest32) {

    public static UnsignedSiwe buildUnsigned(Address eoa, long chainId, String nonce,
                                              Instant issuedAt, Instant expirationTime) {
        Objects.requireNonNull(eoa, "eoa");
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expirationTime, "expirationTime");
        String canonical = SiweMessage.build(eoa, chainId, nonce, issuedAt, expirationTime);
        byte[] digest = SiweMessage.personalSignDigest(canonical);
        return new UnsignedSiwe(eoa, chainId, nonce, issuedAt, expirationTime, canonical, digest);
    }

    public static SignedSiwe attachSignature(UnsignedSiwe unsigned, byte[] sig65) {
        Objects.requireNonNull(unsigned, "unsigned");
        if (sig65 == null || sig65.length != 65) {
            throw new ClobSignatureException(
                    "SIWE signature must be 65 bytes, got " + (sig65 == null ? -1 : sig65.length));
        }
        return new SignedSiwe(unsigned.canonicalMessage, "0x" + HexFormat.of().formatHex(sig65));
    }
}
