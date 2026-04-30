package com.polymarket.clob.exception;

/**
 * EIP-712 订单签名过程中出现问题时抛出。
 * 例如：构造 typed data 失败、远程签名服务返回错误、签名长度异常。
 */
public class ClobSignatureException extends ClobException {
    public ClobSignatureException(String message) {
        super(message);
    }

    public ClobSignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
