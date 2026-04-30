package com.polymarket.clob.ws;

import com.polymarket.clob.ws.message.WsMessage;

/**
 * 订阅消息回调。{@code T} 是该订阅关心的具体消息子类型，{@link WsMessage} 用于
 * 兼容多类型聚合订阅（如 {@code subscribeUserEvents} 同时收 order 与 trade）。
 *
 * <p>{@code onMessage} 在 {@link WebSocketConfig#listenerExecutor()} 上执行，可以阻塞，
 * 但不要在里面再发起新的 subscribe/unsubscribe（会阻塞同一线程）；如有需要请异步派发。</p>
 *
 * @param <T> 订阅期望接收的消息类型上界
 */
@FunctionalInterface
public interface SubscriptionListener<T extends WsMessage> {

    /** 收到匹配本订阅的消息时调用。 */
    void onMessage(T message);

    /**
     * 路由 / 反序列化阶段抛错时回调（可选）。默认空实现，调用方可只关心
     * {@link #onMessage(WsMessage)}。
     */
    default void onError(Throwable error) {
        // no-op
    }
}
