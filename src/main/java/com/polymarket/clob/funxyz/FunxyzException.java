package com.polymarket.clob.funxyz;

/**
 * fun.xyz 调用失败统一异常类型。
 *
 * <p>{@link #httpStatus()} 语义：
 * <ul>
 *   <li>{@code -1}：传输失败（IO / TLS / DNS / timeout）</li>
 *   <li>正数：HTTP 状态码（包含 200 + 业务错，例如 {@code blocked == true} 或 schema 不合法）</li>
 * </ul>
 */
public final class FunxyzException extends RuntimeException {

    static final String BLOCKED_MESSAGE = "eoa blocked by fun.xyz";

    private final int httpStatus;

    public FunxyzException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public FunxyzException(String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public boolean isTransport() {
        return httpStatus < 0;
    }

    public boolean isBlocked() {
        return BLOCKED_MESSAGE.equals(getMessage());
    }
}
