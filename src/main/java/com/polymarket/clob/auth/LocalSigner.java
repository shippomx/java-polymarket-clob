package com.polymarket.clob.auth;

import com.polymarket.clob.exception.ClobAuthException;
import com.polymarket.clob.exception.ClobSignatureException;
import com.polymarket.clob.model.Address;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Sign;

import java.math.BigInteger;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 本地私钥签名器。构造即派生地址；持有 {@link Credentials} 不提供 getter，避免私钥外泄。
 *
 * <p>首版采用 {@link CompletableFuture#completedFuture(Object)} 同步实现——EC 签名本身
 * 是 CPU 密集但毫秒级，没必要占用 common pool；后续 Plan 5 的 {@code RemoteSigner}
 * 接入网络 IO 时自会走真正异步路径。</p>
 */
public final class LocalSigner implements Signer {

    private final Credentials credentials;
    private final Address address;

    private LocalSigner(Credentials credentials) {
        this.credentials = credentials;
        this.address = Address.fromHex(credentials.getAddress());
    }

    public static LocalSigner fromPrivateKey(String hex) {
        Objects.requireNonNull(hex, "hex");
        String body = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        if (body.length() != 64) {
            throw new ClobAuthException(
                    "Private key hex must be 32 bytes (64 hex chars); got " + body.length());
        }
        try {
            return new LocalSigner(Credentials.create(body));
        } catch (Exception e) {
            throw new ClobAuthException("Invalid private key hex", e);
        }
    }

    public static LocalSigner fromPrivateKey(BigInteger pk) {
        Objects.requireNonNull(pk, "pk");
        try {
            return new LocalSigner(Credentials.create(ECKeyPair.create(pk)));
        } catch (Exception e) {
            throw new ClobAuthException("Invalid private key", e);
        }
    }

    @Override
    public Address address() {
        return address;
    }

    @Override
    public CompletableFuture<byte[]> signHash(byte[] digest32) {
        if (digest32 == null || digest32.length != 32) {
            return CompletableFuture.failedFuture(
                    new ClobSignatureException("digest must be 32 bytes, got "
                            + (digest32 == null ? "null" : digest32.length)));
        }
        try {
            Sign.SignatureData sd = Sign.signMessage(digest32, credentials.getEcKeyPair(), false);
            byte[] r = sd.getR();
            byte[] s = sd.getS();
            byte[] v = sd.getV();
            if (r.length != 32 || s.length != 32 || v.length != 1) {
                return CompletableFuture.failedFuture(new ClobSignatureException(
                        "Unexpected signature shape: r=" + r.length
                                + " s=" + s.length + " v=" + v.length));
            }
            byte[] out = new byte[65];
            System.arraycopy(r, 0, out, 0, 32);
            System.arraycopy(s, 0, out, 32, 32);
            out[64] = v[0];
            return CompletableFuture.completedFuture(out);
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(
                    new ClobSignatureException("Failed to sign digest", t));
        }
    }
}
