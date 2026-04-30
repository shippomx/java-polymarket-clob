package com.polymarket.clob.exception;

/**
 * SDK 所有 runtime 异常的父类。
 *
 * <p>所有返回 {@link java.util.concurrent.CompletableFuture} 的 IO 方法在失败时会把
 * 此家族异常放入 future（以 {@code CompletionException} 包装）。调用方可以按分层捕获：
 * <ul>
 *   <li>{@link ClobApiException} — 上游返回非 2xx（本地拿到了 HTTP 响应）</li>
 *   <li>{@link ClobTransportException} — 传输层失败：超时 / 连接失败 / TLS / 中断</li>
 *   <li>{@link ClobAuthException} — L1 / L2 认证失败</li>
 *   <li>{@link ClobSignatureException} — EIP-712 签名失败</li>
 *   <li>{@link ClobSerializationException} — JSON 编解码失败</li>
 * </ul>
 */
public class ClobException extends RuntimeException {
    public ClobException(String message) {
        super(message);
    }

    public ClobException(String message, Throwable cause) {
        super(message, cause);
    }
}
