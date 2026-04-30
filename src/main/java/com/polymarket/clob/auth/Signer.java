package com.polymarket.clob.auth;

import com.polymarket.clob.model.Address;

import java.util.concurrent.CompletableFuture;

/**
 * SDK 内所有签名能力的统一出口。
 *
 * <p>本接口刻意保持最小：既能被 {@link LocalSigner} 直接实现（持有私钥），
 * 也能在后续 Plan 5 的 Builder 模式下被 {@code RemoteSigner} 包成 HTTP 转发，
 * 而不会泄漏任何密钥材料到 SDK 其它地方。</p>
 */
public interface Signer {

    /** 对应签名者的 EOA 地址。 */
    Address address();

    /**
     * 对 32 字节摘要签名，返回 65 字节 {@code r || s || v}。
     *
     * <p>Polymarket 上游不做 EIP-155 offset：{@code v} 直接是 27 或 28。调用方拼接 hex
     * 时直接 {@code "0x" + HEX(r||s||v)} 即可；不要再做 {@code v -= 27} 修正。</p>
     *
     * <p>失败以 {@link com.polymarket.clob.exception.ClobSignatureException} 异常完成。</p>
     */
    CompletableFuture<byte[]> signHash(byte[] digest32);
}
