package com.polymarket.clob.ws;

/**
 * WebSocket 连接生命周期状态。状态机：
 *
 * <pre>
 *   DISCONNECTED ──start──▶ CONNECTING ──onOpen──▶ CONNECTED
 *                              │                       │
 *                              │                  onClose/onError
 *                              ▼                       ▼
 *                          RECONNECTING ◀────── (backoff sleep)
 *                              │
 *                            close()
 *                              ▼
 *                            CLOSED
 * </pre>
 *
 * <p>对应 Rust {@code ConnectionState}。Java 这里保持纯 enum、不带 attempt 数据，
 * 重连尝试次数在 {@code WsConnection.reconnectAttempts()} 单独暴露。</p>
 */
public enum ConnectionState {
    /** 尚未发起连接。 */
    DISCONNECTED,
    /** 正在握手（首次连接或重连中）。 */
    CONNECTING,
    /** 连接已打开，可收发消息。 */
    CONNECTED,
    /** 上一次连接断开，等待 backoff 后会再次进入 {@link #CONNECTING}。 */
    RECONNECTING,
    /** 用户主动调用 {@code close()}，永久终态，不会再发起重连。 */
    CLOSED;

    /** 是否处于活动可用状态。 */
    public boolean isActive() {
        return this == CONNECTED;
    }

    /** 是否处于"用户主动关闭"的终态。 */
    public boolean isTerminated() {
        return this == CLOSED;
    }
}
