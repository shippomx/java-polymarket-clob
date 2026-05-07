package com.polymarket.clob.gamma;

/** Gamma 鉴权失败：SIWE 文本错误、时钟漂移、cookie 过期等。 */
public class GammaAuthException extends RuntimeException {
    public GammaAuthException(String message) { super(message); }
    public GammaAuthException(String message, Throwable cause) { super(message, cause); }
}
