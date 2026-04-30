package com.polymarket.clob.ws;

import com.polymarket.clob.model.Hash32;
import com.polymarket.clob.ws.message.WsMessage;
import com.polymarket.clob.ws.request.Channel;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * 订阅句柄。由 {@link ClobWebSocketClient}（或认证态等价物）的 {@code subscribe*} 方法返回，
 * 用户调用 {@link #cancel()} 关闭单个订阅，不影响同一连接上的其它订阅。
 *
 * <p>对应 spec §10 demo 中的 "{@code Subscription sub = ws.subscribe...}" 句柄；
 * 也是 Rust {@code SubscriptionInfo} 的等价物。</p>
 *
 * <p>说明：</p>
 * <ul>
 *   <li>{@link #id()} 是订阅 UUID，仅用于内部 registry / 日志；</li>
 *   <li>{@link #channel()} 表示这是 market 还是 user 订阅；</li>
 *   <li>{@code matcher} 谓词决定哪些 {@link WsMessage} 被路由到本订阅；</li>
 *   <li>{@code assetIds} / {@code markets} 用于断线重连后 wire 上的重新订阅 envelope。</li>
 * </ul>
 *
 * <p>{@link #cancel()} 是幂等的，多次调用安全。</p>
 */
public final class Subscription {

    private final String id;
    private final Channel channel;
    private final Predicate<WsMessage> matcher;
    @SuppressWarnings("rawtypes")
    private final SubscriptionListener listener;
    private final List<BigInteger> assetIds;
    private final List<Hash32> markets;
    private final Runnable canceller;
    private final AtomicBoolean active = new AtomicBoolean(true);

    @SuppressWarnings("rawtypes")
    Subscription(Channel channel,
                 Predicate<WsMessage> matcher,
                 SubscriptionListener listener,
                 List<BigInteger> assetIds,
                 List<Hash32> markets,
                 Runnable canceller) {
        this.id = UUID.randomUUID().toString();
        this.channel = Objects.requireNonNull(channel, "channel");
        this.matcher = Objects.requireNonNull(matcher, "matcher");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.assetIds = List.copyOf(assetIds);
        this.markets = List.copyOf(markets);
        this.canceller = Objects.requireNonNull(canceller, "canceller");
    }

    public String id() { return id; }
    public Channel channel() { return channel; }

    /** 重连重放 wire envelope 用：所关注的 token id（market channel）。 */
    public List<BigInteger> assetIds() { return assetIds; }

    /** 重连重放 wire envelope 用：所关注的 market condition id（user channel）。 */
    public List<Hash32> markets() { return markets; }

    /** 当前订阅是否还活着（cancel 之前为 true）。 */
    public boolean isActive() { return active.get(); }

    /** 路由用：判断 incoming 消息是否属于本订阅。 */
    boolean matches(WsMessage message) {
        return matcher.test(message);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    void deliver(WsMessage message) {
        try {
            listener.onMessage(message);
        } catch (RuntimeException e) {
            try {
                listener.onError(e);
            } catch (RuntimeException ignored) {
                // listener#onError 也抛了——SDK 不再二次包装
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    void deliverError(Throwable t) {
        try {
            listener.onError(t);
        } catch (RuntimeException ignored) {
            // 避免 onError 异常吞掉路由后续
        }
    }

    /**
     * 取消本订阅。只影响当前 subscription，同一连接上的其它订阅不受影响。
     * 多次调用幂等；调用后 {@link #isActive()} 返回 {@code false}。
     */
    public void cancel() {
        if (active.compareAndSet(true, false)) {
            canceller.run();
        }
    }

    /**
     * 不走 canceller 的 silent cancel —— 仅由 {@code ChannelGateway#close()} 在拆毁 channel
     * 时使用：此时 socket 已经关，发 unsubscribe envelope 既无意义也可能 race，因此只把
     * {@link #active} 置 false，避免最后几条 in-flight 消息再派发到上层 listener。
     */
    void markCancelled() {
        active.set(false);
    }

    @Override
    public String toString() {
        return "Subscription{id=" + id + ", channel=" + channel + ", active=" + active.get() + "}";
    }
}
