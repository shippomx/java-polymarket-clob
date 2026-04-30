package com.polymarket.clob.ws;

import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.model.Hash32;
import com.polymarket.clob.ws.message.OrderMessage;
import com.polymarket.clob.ws.message.TradeMessage;
import com.polymarket.clob.ws.message.WsMessage;
import com.polymarket.clob.ws.request.Channel;

import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Polymarket CLOB **认证态** WebSocket 客户端。typestate：已注入 L2 凭证，
 * 在公开行情订阅之上额外提供 user 通道（订单 / 成交）订阅。
 *
 * <p>由 {@link AuthenticatedClobClient#webSocket()} 入口创建，或通过
 * {@link ClobWebSocketClient#builder()}{@code .authenticated(creds)} 链式升级。
 * 编译期阻断未认证态客户端调用 {@link #subscribeOrders} / {@link #subscribeTrades}。</p>
 */
public final class AuthenticatedClobWebSocketClient extends ClobWebSocketClient {

    private final ApiCredentials credentials;
    private final ChannelGateway userGateway;

    private AuthenticatedClobWebSocketClient(
            URI baseEndpoint,
            WebSocketConfig config,
            com.fasterxml.jackson.databind.ObjectMapper mapper,
            ApiCredentials credentials) {
        this(baseEndpoint, config, mapper, credentials, OutgoingFrameCaptor.NOOP);
    }

    private AuthenticatedClobWebSocketClient(
            URI baseEndpoint,
            WebSocketConfig config,
            com.fasterxml.jackson.databind.ObjectMapper mapper,
            ApiCredentials credentials,
            OutgoingFrameCaptor frameCaptor) {
        super(baseEndpoint, config, mapper, frameCaptor);
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.userGateway = new ChannelGateway(
                Channel.USER,
                channelEndpoint(baseEndpointInternal(), Channel.USER),
                configInternal(),
                mapperInternal(),
                credentials,
                frameCaptorInternal());
    }

    public static AuthenticatedClobWebSocketClient create(ApiCredentials credentials) {
        return create(DEFAULT_ENDPOINT, WebSocketConfig.defaults(), credentials);
    }

    public static AuthenticatedClobWebSocketClient create(
            URI baseEndpoint, WebSocketConfig config, ApiCredentials credentials) {
        return new AuthenticatedClobWebSocketClient(
                baseEndpoint, config, com.polymarket.clob.http.JsonCodec.objectMapper(), credentials);
    }

    /**
     * 从已构建的未认证 client 升级。沿用其 baseEndpoint / config / mapper，
     * 但 user / market gateway 在新对象内独立持有，与原对象互不干扰。
     */
    static AuthenticatedClobWebSocketClient from(ClobWebSocketClient base, ApiCredentials credentials) {
        Objects.requireNonNull(base, "base");
        return new AuthenticatedClobWebSocketClient(
                base.baseEndpointInternal(),
                base.configInternal(),
                base.mapperInternal(),
                credentials,
                base.frameCaptorInternal());
    }

    /** 返回当前持有的 L2 凭证。包内可见，便于上层装配；不向外部包暴露。 */
    ApiCredentials credentials() {
        return credentials;
    }

    public ConnectionState userConnectionState() {
        return userGateway.connectionState();
    }

    /**
     * 订阅 user 通道下的 **订单状态** 事件（{@link OrderMessage}）。markets 为关注的
     * 市场 hash 列表；{@code initial_dump=true} 由 SDK 自动注入，连接首帧会推送当前
     * 全部 open order 快照。
     */
    public Subscription subscribeOrders(
            List<Hash32> markets, SubscriptionListener<OrderMessage> listener) {
        validateMarkets(markets);
        Set<Hash32> targets = new HashSet<>(markets);
        return userGateway.register(
                msg -> msg instanceof OrderMessage o && targets.contains(o.market()),
                listener,
                Collections.emptyList(),
                List.copyOf(markets));
    }

    /**
     * 订阅 user 通道下的 **成交** 事件（{@link TradeMessage}）。
     * markets 列表与 {@link #subscribeOrders} 共享同一条 user connection。
     */
    public Subscription subscribeTrades(
            List<Hash32> markets, SubscriptionListener<TradeMessage> listener) {
        validateMarkets(markets);
        Set<Hash32> targets = new HashSet<>(markets);
        return userGateway.register(
                msg -> msg instanceof TradeMessage t && targets.contains(t.market()),
                listener,
                Collections.emptyList(),
                List.copyOf(markets));
    }

    /**
     * 同时订阅 orders 和 trades，统一通过 {@link WsMessage} 派发。
     * 对于希望"一个 listener 处理所有 user 事件"的简单场景。
     */
    public Subscription subscribeAllUserEvents(
            List<Hash32> markets, SubscriptionListener<WsMessage> listener) {
        validateMarkets(markets);
        Set<Hash32> targets = new HashSet<>(markets);
        return userGateway.register(
                msg -> {
                    if (msg instanceof OrderMessage o) return targets.contains(o.market());
                    if (msg instanceof TradeMessage t) return targets.contains(t.market());
                    return false;
                },
                listener,
                Collections.emptyList(),
                List.copyOf(markets));
    }

    @Override
    public void close() {
        try {
            userGateway.close();
        } finally {
            super.close();
        }
    }

    private static void validateMarkets(List<Hash32> markets) {
        Objects.requireNonNull(markets, "markets");
        if (markets.isEmpty()) {
            throw new IllegalArgumentException("markets must not be empty");
        }
        for (Hash32 m : markets) {
            Objects.requireNonNull(m, "market element");
        }
    }
}
