package com.polymarket.clob.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.http.JsonCodec;
import com.polymarket.clob.ws.message.BookUpdate;
import com.polymarket.clob.ws.message.LastTradePrice;
import com.polymarket.clob.ws.message.PriceChange;
import com.polymarket.clob.ws.message.PriceChangeBatchEntry;
import com.polymarket.clob.ws.message.TickSizeChange;
import com.polymarket.clob.ws.message.WsMessage;
import com.polymarket.clob.ws.request.Channel;

import java.math.BigInteger;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Polymarket CLOB **公开行情** WebSocket 客户端。typestate：未认证态。
 *
 * <p>提供四类公开行情订阅，对应 Rust {@code Client<Unauthenticated>}：</p>
 * <ul>
 *   <li>{@link #subscribeOrderbook(List, SubscriptionListener)} —— 订单簿快照（{@code BookUpdate}）</li>
 *   <li>{@link #subscribePriceChanges(List, SubscriptionListener)} —— 价格变化（{@code PriceChange}）</li>
 *   <li>{@link #subscribeTickSizeChanges(List, SubscriptionListener)} —— tick size 切换</li>
 *   <li>{@link #subscribeLastTradePrices(List, SubscriptionListener)} —— 最近成交价</li>
 * </ul>
 *
 * <p>用户态订阅（订单 / 成交）在认证态客户端 {@link AuthenticatedClobWebSocketClient}
 * 上提供，编译期阻断未认证调用。</p>
 *
 * <p>{@code endpoint} 接受三种形态，内部统一规整成 base URI 后再追加
 * {@code /ws/market} 或 {@code /ws/user}：</p>
 * <ul>
 *   <li>{@code wss://ws-subscriptions-clob.polymarket.com}</li>
 *   <li>{@code wss://ws-subscriptions-clob.polymarket.com/ws}</li>
 *   <li>{@code wss://ws-subscriptions-clob.polymarket.com/ws/market}</li>
 * </ul>
 *
 * <p>该类线程安全：所有 subscribe 调用可并发；底层 connection 在首次订阅时 lazy
 * 建立。{@link #close()} 后所有未取消的订阅句柄会随之失效。</p>
 */
public class ClobWebSocketClient implements AutoCloseable {

    /** Rust SDK 默认 WS endpoint。和 REST 的 {@code clob.polymarket.com} 不同。 */
    public static final URI DEFAULT_ENDPOINT =
            URI.create("wss://ws-subscriptions-clob.polymarket.com");

    private final URI baseEndpoint;
    private final WebSocketConfig config;
    private final ObjectMapper mapper;
    /** 出站 frame 拦截 hook；默认 {@link OutgoingFrameCaptor#NOOP}，仅 parity 测试场景下注入。 */
    private final OutgoingFrameCaptor frameCaptor;
    private final ChannelGateway marketGateway;

    /** 兼容旧 3 参签名，captor 默认 {@link OutgoingFrameCaptor#NOOP}。 */
    ClobWebSocketClient(URI baseEndpoint, WebSocketConfig config, ObjectMapper mapper) {
        this(baseEndpoint, config, mapper, OutgoingFrameCaptor.NOOP);
    }

    /** 由 {@link AuthenticatedClobWebSocketClient} 构造时复用的内部入口。 */
    ClobWebSocketClient(URI baseEndpoint,
                        WebSocketConfig config,
                        ObjectMapper mapper,
                        OutgoingFrameCaptor frameCaptor) {
        this.baseEndpoint = normalizeBaseEndpoint(Objects.requireNonNull(baseEndpoint, "endpoint"));
        this.config = Objects.requireNonNull(config, "config");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.frameCaptor = frameCaptor == null ? OutgoingFrameCaptor.NOOP : frameCaptor;
        this.marketGateway = new ChannelGateway(
                Channel.MARKET,
                channelEndpoint(this.baseEndpoint, Channel.MARKET),
                this.config,
                this.mapper,
                null,
                this.frameCaptor);
    }

    public static ClobWebSocketClient create() {
        return create(DEFAULT_ENDPOINT, WebSocketConfig.defaults());
    }

    public static ClobWebSocketClient create(URI baseEndpoint, WebSocketConfig config) {
        return new ClobWebSocketClient(baseEndpoint, config, JsonCodec.objectMapper());
    }

    public static Builder builder() {
        return new Builder();
    }

    public URI baseEndpoint() { return baseEndpoint; }
    public URI marketEndpoint() { return channelEndpoint(baseEndpoint, Channel.MARKET); }
    public URI userEndpoint() { return channelEndpoint(baseEndpoint, Channel.USER); }

    /** 当前 market connection 状态（未发起订阅时返回 {@link ConnectionState#DISCONNECTED}）。 */
    public ConnectionState marketConnectionState() {
        return marketGateway.connectionState();
    }

    /** 当前活跃订阅总数（所有 channel 之和）。 */
    public int subscriptionCount() {
        return marketGateway.subscriptionCount();
    }

    // ---------------- Market 订阅 ----------------

    /** 订阅订单簿快照：每次撮合 / 撤单都推送当前完整 book 状态。 */
    public Subscription subscribeOrderbook(
            List<BigInteger> assetIds, SubscriptionListener<BookUpdate> listener) {
        validateAssetIds(assetIds);
        Set<BigInteger> targets = new HashSet<>(assetIds);
        return marketGateway.register(
                msg -> msg instanceof BookUpdate b && targets.contains(b.assetId()),
                listener,
                List.copyOf(assetIds),
                Collections.emptyList());
    }

    /**
     * 订阅价格变化：服务端会把同 market 下若干 tick 合并到一条 {@link PriceChange}，
     * 内含 {@link PriceChangeBatchEntry} 列表；调用方按 {@code assetId} 自行过滤。
     *
     * <p>SDK 在 wire 层上只对感兴趣 asset 发 subscribe，但路由侧只要 batch 中
     * <i>任何一项</i> {@code asset_id} 命中就会派发整条消息——这是与 Rust 行为一致的
     * 选择，便于调用方做整批一致性处理。</p>
     */
    public Subscription subscribePriceChanges(
            List<BigInteger> assetIds, SubscriptionListener<PriceChange> listener) {
        validateAssetIds(assetIds);
        Set<BigInteger> targets = new HashSet<>(assetIds);
        return marketGateway.register(
                msg -> msg instanceof PriceChange p && p.priceChanges().stream()
                        .map(PriceChangeBatchEntry::assetId).anyMatch(targets::contains),
                listener,
                List.copyOf(assetIds),
                Collections.emptyList());
    }

    /** 订阅 tick size 变化。 */
    public Subscription subscribeTickSizeChanges(
            List<BigInteger> assetIds, SubscriptionListener<TickSizeChange> listener) {
        validateAssetIds(assetIds);
        Set<BigInteger> targets = new HashSet<>(assetIds);
        return marketGateway.register(
                msg -> msg instanceof TickSizeChange t && targets.contains(t.assetId()),
                listener,
                List.copyOf(assetIds),
                Collections.emptyList());
    }

    /** 订阅最近成交价。 */
    public Subscription subscribeLastTradePrices(
            List<BigInteger> assetIds, SubscriptionListener<LastTradePrice> listener) {
        validateAssetIds(assetIds);
        Set<BigInteger> targets = new HashSet<>(assetIds);
        return marketGateway.register(
                msg -> msg instanceof LastTradePrice l && targets.contains(l.assetId()),
                listener,
                List.copyOf(assetIds),
                Collections.emptyList());
    }

    @Override
    public void close() {
        marketGateway.close();
    }

    // ---------------- 给 Authenticated 子类访问的内部状态 ----------------

    /**
     * 暴露给 {@link AuthenticatedClobWebSocketClient} 的内部访问入口；外部包不可见。
     * 这样可以共享同一份 baseEndpoint / WebSocketConfig / ObjectMapper，避免重复传参。
     */
    URI baseEndpointInternal() { return baseEndpoint; }
    WebSocketConfig configInternal() { return config; }
    ObjectMapper mapperInternal() { return mapper; }
    OutgoingFrameCaptor frameCaptorInternal() { return frameCaptor; }

    // ---------------- Helpers ----------------

    private static void validateAssetIds(List<BigInteger> assetIds) {
        Objects.requireNonNull(assetIds, "assetIds");
        if (assetIds.isEmpty()) {
            throw new IllegalArgumentException("assetIds must not be empty");
        }
        for (BigInteger id : assetIds) {
            Objects.requireNonNull(id, "assetId element");
        }
    }

    /**
     * 与 Rust {@code normalize_base_endpoint} 一致：剥离尾部 {@code /ws/market} /
     * {@code /ws/user} / {@code /ws} 后缀，得到纯 base URI；也容忍尾部多余的 {@code /}。
     */
    static URI normalizeBaseEndpoint(URI input) {
        String s = input.toString();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.endsWith("/ws/market")) s = s.substring(0, s.length() - "/ws/market".length());
        else if (s.endsWith("/ws/user")) s = s.substring(0, s.length() - "/ws/user".length());
        else if (s.endsWith("/ws")) s = s.substring(0, s.length() - "/ws".length());
        return URI.create(s);
    }

    static URI channelEndpoint(URI base, Channel channel) {
        String s = base.toString();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return URI.create(s + "/ws/" + channel.pathSegment());
    }

    public static final class Builder {
        private URI endpoint = DEFAULT_ENDPOINT;
        private WebSocketConfig config = WebSocketConfig.defaults();
        private ObjectMapper mapper;
        private OutgoingFrameCaptor frameCaptor = OutgoingFrameCaptor.NOOP;

        public Builder endpoint(URI uri) { this.endpoint = Objects.requireNonNull(uri); return this; }
        public Builder config(WebSocketConfig c) { this.config = Objects.requireNonNull(c); return this; }
        public Builder objectMapper(ObjectMapper m) { this.mapper = m; return this; }

        /** 注入出站 frame 拦截 hook；仅 parity 测试链路使用，传 null 等价于 {@link OutgoingFrameCaptor#NOOP}。 */
        public Builder outgoingFrameCaptor(OutgoingFrameCaptor c) {
            this.frameCaptor = c == null ? OutgoingFrameCaptor.NOOP : c;
            return this;
        }

        public ClobWebSocketClient build() {
            return new ClobWebSocketClient(
                    endpoint,
                    config,
                    mapper == null ? JsonCodec.objectMapper() : mapper,
                    frameCaptor);
        }

        /**
         * Authenticated 升级捷径：构建未认证 client 后立即升级。便于 builder 链式调用。
         */
        public AuthenticatedClobWebSocketClient authenticated(ApiCredentials credentials) {
            return AuthenticatedClobWebSocketClient.from(build(), credentials);
        }
    }
}
