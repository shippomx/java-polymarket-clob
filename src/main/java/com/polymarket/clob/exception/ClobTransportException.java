package com.polymarket.clob.exception;

/**
 * 传输层错误：连接失败、超时、DNS 失败、TLS 握手失败、连接重置、线程中断等。
 *
 * <p>区别于 {@link ClobApiException}（上游返回了非 2xx，但本地已成功拿到响应），
 * 此异常表示客户端<strong>没能从上游拿到 HTTP 响应</strong>。调用方可以据此与
 * {@code ClobApiException} 区分出 "重试可能有效" 的场景。</p>
 *
 * <p>原因一律附在 {@link #getCause()} 中（通常是 {@code IOException} /
 * {@code HttpTimeoutException} / {@code InterruptedException}）。</p>
 */
public class ClobTransportException extends ClobException {

    public ClobTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
