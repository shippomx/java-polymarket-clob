package com.polymarket.clob.auth;

import com.polymarket.clob.model.Address;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 构造获取 L2 API 凭证所需的 L1 请求头（EOA EIP-712 签名）。
 *
 * <p>对应 Rust {@code rs-clob-client/src/auth.rs} 中的 {@code l1::create_headers}：
 * <ul>
 *   <li>构造 {@link ClobAuth} typed data（primary type）；</li>
 *   <li>用 {@code ClobAuthDomain/1/chainId} 作为 EIP-712 domain；</li>
 *   <li>{@link Signer} 对 32 字节 {@code signingHash} 产生 {@code r||s||v}；</li>
 *   <li>header {@code POLY_ADDRESS} 为小写 hex，{@code POLY_SIGNATURE} 为 {@code 0x} 前缀 65 字节 hex。</li>
 * </ul>
 * </p>
 */
public final class L1HeaderBuilder {

    public static final String POLY_ADDRESS = "POLY_ADDRESS";
    public static final String POLY_NONCE = "POLY_NONCE";
    public static final String POLY_SIGNATURE = "POLY_SIGNATURE";
    public static final String POLY_TIMESTAMP = "POLY_TIMESTAMP";

    private L1HeaderBuilder() {}

    /**
     * @param signer    L1 私钥签名器
     * @param chainId   EIP-712 domain 的 {@code chainId}
     * @param timestamp Unix 秒
     * @param nonce     nonce；为 {@code null} 视作 0，与 Rust 默认保持一致
     */
    public static CompletableFuture<Map<String, String>> build(
            Signer signer,
            long chainId,
            long timestamp,
            BigInteger nonce) {
        Objects.requireNonNull(signer, "signer");
        BigInteger n = nonce == null ? BigInteger.ZERO : nonce;
        Address address = signer.address();
        ClobAuth auth = ClobAuth.of(address, timestamp, n);
        byte[] digest = Eip712TypedData.hashClobAuth(auth, chainId);
        return signer.signHash(digest).thenApply(sig -> {
            String signatureHex = "0x" + HexFormat.of().formatHex(sig);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put(POLY_ADDRESS, address.toLowerHex());
            headers.put(POLY_NONCE, n.toString());
            headers.put(POLY_SIGNATURE, signatureHex);
            headers.put(POLY_TIMESTAMP, Long.toString(timestamp));
            return headers;
        });
    }
}
