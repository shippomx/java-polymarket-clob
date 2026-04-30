package com.polymarket.clob.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.exception.ClobSerializationException;
import com.polymarket.clob.model.Hash32;
import com.polymarket.clob.ws.message.WsMessage;
import com.polymarket.clob.ws.request.Channel;
import com.polymarket.clob.ws.request.SubscriptionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * 单 channel（market 或 user）的端到端管道：
 * <pre>
 *   {@link WsConnection}  ◀── network frame ──▶  ChannelGateway  ─── route ──▶  Subscription[]
 *                                                       │
 *                                                       └─ on reconnect: replay subscribe envelopes
 * </pre>
 *
 * <p>对应 Rust {@code ChannelResources}（{@code rs-clob-client/src/clob/ws/client.rs}）。
 * Java 这里把 connection / 注册表 / 重放 / 反序列化 / 路由都聚合在同一类，避免
 * Rust 那种 broadcast channel + interest tracker 的复杂度——单 listener-per-sub 模型
 * 已经足够。</p>
 *
 * <p>线程安全：</p>
 * <ul>
 *   <li>{@code subscriptions} 用 {@link ConcurrentHashMap#newKeySet()}；</li>
 *   <li>{@code conn} lazy 创建，{@code AtomicReference} CAS；</li>
 *   <li>路由、replay 自身无写竞争（只读 set），register/cancel 间互不干扰。</li>
 * </ul>
 *
 * <p>所有 wire envelope 序列化失败统一抛 {@link ClobSerializationException}，订阅句柄
 * 保留在 set 内但 {@code wire} 没发出——使用方应处理 future 异常或回调日志。</p>
 */
final class ChannelGateway implements MessageHandler, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ChannelGateway.class);

    private final Channel channelType;
    private final URI endpoint;
    private final WebSocketConfig config;
    private final ObjectMapper mapper;
    private final ApiCredentials credentials; // null = unauthenticated market channel
    /** 出站 frame 拦截 hook；默认 {@link OutgoingFrameCaptor#NOOP}，仅 parity 测试场景下注入。 */
    private final OutgoingFrameCaptor frameCaptor;

    private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
    private final AtomicReference<WsConnection> conn = new AtomicReference<>();
    /** {@link #close()} 调用后置 true；后续 {@code register()} 直接抛错，不允许复活。 */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 兼容旧 5 参签名：默认 captor 为 {@link OutgoingFrameCaptor#NOOP}；测试和向后兼容路径继续可用。
     */
    ChannelGateway(Channel channelType,
                   URI endpoint,
                   WebSocketConfig config,
                   ObjectMapper mapper,
                   ApiCredentials credentials) {
        this(channelType, endpoint, config, mapper, credentials, OutgoingFrameCaptor.NOOP);
    }

    ChannelGateway(Channel channelType,
                   URI endpoint,
                   WebSocketConfig config,
                   ObjectMapper mapper,
                   ApiCredentials credentials,
                   OutgoingFrameCaptor frameCaptor) {
        this.channelType = Objects.requireNonNull(channelType, "channelType");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.config = Objects.requireNonNull(config, "config");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.credentials = credentials;
        this.frameCaptor = frameCaptor == null ? OutgoingFrameCaptor.NOOP : frameCaptor;
        if (channelType == Channel.USER && credentials == null) {
            throw new IllegalArgumentException("USER channel requires credentials");
        }
    }

    // ---------- 公共 API（包内可见）----------

    Subscription register(Predicate<WsMessage> matcher,
                          SubscriptionListener<? extends WsMessage> listener,
                          List<BigInteger> assetIds,
                          List<Hash32> markets) {
        if (closed.get()) {
            throw new IllegalStateException(
                    "ChannelGateway[" + channelType + "] already closed; cannot register new subscription");
        }
        // canceller 占位，先创建 sub 才能拿到 id；用闭包延迟绑定。
        Runnable[] cancellerHolder = new Runnable[1];
        Subscription sub = new Subscription(channelType, matcher, listener, assetIds, markets,
                () -> cancellerHolder[0].run());
        cancellerHolder[0] = () -> handleCancel(sub);

        subscriptions.add(sub);

        WsConnection c = ensureConnection();
        // 如果连接已就绪立即发；否则 onConnected 回调里 replay 时一并发。
        if (c.state() == ConnectionState.CONNECTED) {
            sendSubscribeEnvelope(c, sub);
        }
        return sub;
    }

    int subscriptionCount() {
        return subscriptions.size();
    }

    ConnectionState connectionState() {
        WsConnection c = conn.get();
        return c == null ? ConnectionState.DISCONNECTED : c.state();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return; // 幂等：重复 close 直接返回
        }
        WsConnection c = conn.getAndSet(null);
        if (c != null) {
            c.close();
        }
        // 标记所有未取消的订阅为非 active，防止它们在 close 之后还从 stale 引用收到消息派发。
        for (Subscription sub : subscriptions) {
            sub.markCancelled();
        }
        subscriptions.clear();
    }

    // ---------- MessageHandler ----------

    @Override
    public void onText(String text) {
        // 与 Rust 的 parse_if_interested 对齐：wire 上同时存在两种形态——
        //   1) 单条 object：{"event_type":"book", ...}
        //   2) 顶层 array  ：[{"event_type":"book", ...}, {"event_type":"price_change", ...}]
        // 缺 event_type / 未在 sealed permit 列表里的 elem 应静默跳过（允许上游灰度新事件
        // 而不阻塞老 client）。
        List<WsMessage> messages = parseFrames(text);
        if (messages.isEmpty()) {
            return;
        }
        for (WsMessage msg : messages) {
            for (Subscription sub : subscriptions) {
                if (sub.isActive() && sub.matches(msg)) {
                    sub.deliver(msg);
                }
            }
        }
    }

    /**
     * 解析一帧 wire 文本，宽容地兼容单 obj / 顶层 array。
     *
     * <ul>
     *   <li>非法 JSON 顶层结构：通过 {@link ClobSerializationException} 通知所有订阅；</li>
     *   <li>顶层 obj 缺 {@code event_type} 或 type 未识别：静默跳过；</li>
     *   <li>顶层 array 内某条解析失败：仅跳过该条，不影响其它条。</li>
     * </ul>
     */
    private List<WsMessage> parseFrames(String text) {
        JsonNode root;
        try {
            root = mapper.readTree(text);
        } catch (Exception e) {
            LOG.debug("Failed to parse WS frame as JSON: {} (text={})", e.toString(), abbreviate(text));
            ClobSerializationException wrapped = new ClobSerializationException(
                    "Failed to parse WS frame", e);
            for (Subscription sub : subscriptions) {
                sub.deliverError(wrapped);
            }
            return List.of();
        }

        if (root.isObject()) {
            WsMessage one = tryConvert(root);
            return one == null ? List.of() : List.of(one);
        }
        if (root.isArray()) {
            List<WsMessage> out = new ArrayList<>(root.size());
            for (JsonNode elem : root) {
                if (!elem.isObject()) continue;
                WsMessage parsed = tryConvert(elem);
                if (parsed != null) out.add(parsed);
            }
            return out;
        }
        // 顶层是 null / 字符串 / 数字：直接忽略。
        LOG.debug("Ignoring non-object/array WS frame: {}", abbreviate(text));
        return List.of();
    }

    /** 尝试把单个 JSON 对象转成 {@link WsMessage}；失败返回 {@code null} 而非抛错。 */
    private WsMessage tryConvert(JsonNode node) {
        JsonNode typeNode = node.get("event_type");
        if (typeNode == null || !typeNode.isTextual()) {
            return null;
        }
        try {
            return mapper.treeToValue(node, WsMessage.class);
        } catch (Exception e) {
            // 未知 event_type 或字段不兼容——静默跳过，避免一条灰度消息把整批 abort。
            LOG.debug("Skipping unknown/invalid WS event_type='{}': {}",
                    typeNode.asText(), e.toString());
            return null;
        }
    }

    @Override
    public void onError(Throwable error) {
        for (Subscription sub : subscriptions) {
            sub.deliverError(error);
        }
    }

    // ---------- 内部辅助 ----------

    private WsConnection ensureConnection() {
        WsConnection existing = conn.get();
        if (existing != null) return existing;

        WsConnection created = new WsConnection(endpoint, config, this);
        if (!conn.compareAndSet(null, created)) {
            // 另一线程同时创建了 connection——丢弃 created
            return conn.get();
        }
        created.addConnectedListener(this::replayAll);
        // 触发首次连接（async）；首连失败由 WsConnection 自行进入重连流程。
        created.connect();
        return created;
    }

    private void handleCancel(Subscription sub) {
        subscriptions.remove(sub);
        WsConnection c = conn.get();
        if (c == null || c.state() != ConnectionState.CONNECTED) {
            // 没建连或断线——server 那侧也就不需要 unsubscribe；下次 replay 时该 sub 已经不在集合里。
            return;
        }
        try {
            String json = buildUnsubscribeEnvelope(sub);
            // 在 sendText 之前同步回调 captor，保证"传给 captor 的就是发出去的"
            frameCaptor.capture(channelType, endpoint, json);
            c.sendText(json).whenComplete((unused, ex) -> {
                if (ex != null) LOG.debug("unsubscribe envelope send failed: {}", ex.toString());
            });
        } catch (RuntimeException e) {
            LOG.debug("Failed to build unsubscribe envelope: {}", e.toString());
        }
    }

    private void replayAll() {
        WsConnection c = conn.get();
        if (c == null) return;
        for (Subscription sub : subscriptions) {
            if (sub.isActive()) {
                sendSubscribeEnvelope(c, sub);
            }
        }
    }

    private void sendSubscribeEnvelope(WsConnection c, Subscription sub) {
        try {
            String json = buildSubscribeEnvelope(sub);
            // 在 sendText 之前同步回调 captor，保证"传给 captor 的就是发出去的"
            frameCaptor.capture(channelType, endpoint, json);
            c.sendText(json).whenComplete((unused, ex) -> {
                if (ex != null) {
                    LOG.warn("Failed to send subscribe envelope: {}", ex.toString());
                    sub.deliverError(ex);
                }
            });
        } catch (RuntimeException e) {
            LOG.warn("Failed to build subscribe envelope: {}", e.toString());
            sub.deliverError(e);
        }
    }

    private String buildSubscribeEnvelope(Subscription sub) {
        SubscriptionRequest req = (channelType == Channel.MARKET)
                ? SubscriptionRequest.market(sub.assetIds(), true)
                : SubscriptionRequest.user(sub.markets());
        return (channelType == Channel.MARKET)
                ? req.toJson(mapper)
                : req.toAuthenticatedJson(mapper, credentials);
    }

    private String buildUnsubscribeEnvelope(Subscription sub) {
        SubscriptionRequest req = (channelType == Channel.MARKET)
                ? SubscriptionRequest.marketUnsubscribe(sub.assetIds())
                : SubscriptionRequest.userUnsubscribe(sub.markets());
        return (channelType == Channel.MARKET)
                ? req.toJson(mapper)
                : req.toAuthenticatedJson(mapper, credentials);
    }

    private static String abbreviate(String s) {
        return s.length() <= 256 ? s : s.substring(0, 256) + "...(truncated " + s.length() + ")";
    }
}
