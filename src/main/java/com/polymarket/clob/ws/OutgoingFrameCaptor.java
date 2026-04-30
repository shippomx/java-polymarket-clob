package com.polymarket.clob.ws;

import com.polymarket.clob.ws.request.Channel;

import java.net.URI;

/**
 * WebSocket 出站文本 frame 拦截 hook。<b>仅供 parity 测试使用</b>，生产路径默认走
 * {@link #NOOP}。
 *
 * <p>在 {@code WsConnection.sendText(json)} 调用之前同步触发；{@code body} 为最终
 * wire 上发送的 JSON 字符串（market channel 走 {@code SubscriptionRequest.toJson}，
 * user channel 走 {@code toAuthenticatedJson}），即"传给 captor 的就是发出去的"。</p>
 *
 * <p>{@code endpoint} 是该 channel 实际连接的 URI（含 {@code /ws/market} 或
 * {@code /ws/user} 后缀），方便 parity 比对在多 channel 场景下区分来源。</p>
 *
 * <p>实现 {@link OutgoingFrameCaptor} 需注意：本 hook 在 SDK 内部线程同步调用，
 * 抛错会冒泡到 subscribe 调用方；高频订阅时不应做阻塞 IO。</p>
 */
@FunctionalInterface
public interface OutgoingFrameCaptor {

    /**
     * 出帧前同步回调。
     *
     * @param channel  当前帧所属 channel（{@link Channel#MARKET} / {@link Channel#USER}）
     * @param endpoint 该 channel 的连接 URI
     * @param body     即将通过 {@code sendText} 发出的 JSON 字符串
     */
    void capture(Channel channel, URI endpoint, String body);

    /** 默认空实现，生产路径使用，零开销。 */
    OutgoingFrameCaptor NOOP = (c, u, b) -> { };
}
