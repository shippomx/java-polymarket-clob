package com.polymarket.clob.ws;

/**
 * {@link WsConnection} 收到完整 WebSocket text frame 后的回调入口。
 *
 * <p>{@code onText} 在配置的 {@code listenerExecutor} 上执行，用户代码可以阻塞——
 * 不会反压网络 IO 线程；但同一个 handler 不保证多个 frame 之间是并发安全的，
 * 高并发场景请自行加同步。</p>
 *
 * <p>{@code onError} 在重连流程中（IO 异常/握手失败）触发；调用方一般用于打日志
 * 或上报，重连本身已由 {@link WsConnection} 接管，无需手动重连。</p>
 */
public interface MessageHandler {

    /** 收到完整 text frame 时调用，{@code text} 是合并后的 UTF-8 字符串。 */
    void onText(String text);

    /** 默认空实现：连接异常 / 反序列化失败时调用，{@code error} 必非空。 */
    default void onError(Throwable error) {
        // no-op
    }
}
