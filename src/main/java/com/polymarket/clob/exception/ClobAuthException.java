package com.polymarket.clob.exception;

/**
 * L1（钱包 EIP-712 签名）或 L2（API-Key HMAC）认证失败时抛出。
 * 例如：缺失 API 凭证、HMAC 签名错误、服务端返回 401/403。
 */
public class ClobAuthException extends ClobException {
    public ClobAuthException(String message) {
        super(message);
    }

    public ClobAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
