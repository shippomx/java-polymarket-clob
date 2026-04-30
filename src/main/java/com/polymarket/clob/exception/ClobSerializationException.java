package com.polymarket.clob.exception;

/**
 * JSON 编解码或其他序列化操作失败时抛出。
 * 底层 cause 通常是 Jackson 的 {@code JsonProcessingException} 或 {@code IOException}。
 */
public class ClobSerializationException extends ClobException {
    public ClobSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
