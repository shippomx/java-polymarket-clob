package com.polymarket.clob.ws;

import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.model.Hash32;
import com.polymarket.clob.ws.message.BookUpdate;
import com.polymarket.clob.ws.message.OrderMessage;
import com.polymarket.clob.ws.request.Channel;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * 纯函数 / 校验逻辑的单元测试，不需要真起 socket。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>{@link ClobWebSocketClient#normalizeBaseEndpoint(URI)} 与
 *       {@link ClobWebSocketClient#channelEndpoint(URI, Channel)} 的边界形态；</li>
 *   <li>各 subscribe* 入参校验（空列表 / null 元素 / null listener）；</li>
 *   <li>{@code USER} channel 必须持有 credentials；</li>
 *   <li>{@link Subscription#cancel()} 幂等性。</li>
 * </ul>
 */
class ClobWebSocketClientTest {

    private static final BigInteger ASSET = new BigInteger("12345");
    private static final Hash32 MARKET = Hash32.fromHex(
            "0x0000000000000000000000000000000000000000000000000000000000000001");
    private static final ApiCredentials CREDS = new ApiCredentials(
            "00000000-0000-0000-0000-000000000000", "secret", "passphrase");

    @Test
    void normalizeBaseEndpointStripsKnownSuffixes() {
        assertThat(ClobWebSocketClient.normalizeBaseEndpoint(
                URI.create("wss://host.example.com")))
                .isEqualTo(URI.create("wss://host.example.com"));
        assertThat(ClobWebSocketClient.normalizeBaseEndpoint(
                URI.create("wss://host.example.com/")))
                .isEqualTo(URI.create("wss://host.example.com"));
        assertThat(ClobWebSocketClient.normalizeBaseEndpoint(
                URI.create("wss://host.example.com/ws")))
                .isEqualTo(URI.create("wss://host.example.com"));
        assertThat(ClobWebSocketClient.normalizeBaseEndpoint(
                URI.create("wss://host.example.com/ws/market")))
                .isEqualTo(URI.create("wss://host.example.com"));
        assertThat(ClobWebSocketClient.normalizeBaseEndpoint(
                URI.create("wss://host.example.com/ws/user")))
                .isEqualTo(URI.create("wss://host.example.com"));
        // 用户额外加了无关 path：保留，下层只剥已知后缀
        assertThat(ClobWebSocketClient.normalizeBaseEndpoint(
                URI.create("wss://host.example.com/proxy/v1/")))
                .isEqualTo(URI.create("wss://host.example.com/proxy/v1"));
    }

    @Test
    void channelEndpointAppendsSuffix() {
        URI base = URI.create("wss://host.example.com");
        assertThat(ClobWebSocketClient.channelEndpoint(base, Channel.MARKET))
                .isEqualTo(URI.create("wss://host.example.com/ws/market"));
        assertThat(ClobWebSocketClient.channelEndpoint(base, Channel.USER))
                .isEqualTo(URI.create("wss://host.example.com/ws/user"));
        // base 带尾部斜杠也兼容
        assertThat(ClobWebSocketClient.channelEndpoint(URI.create("wss://h/"), Channel.MARKET))
                .isEqualTo(URI.create("wss://h/ws/market"));
    }

    @Test
    void clientCacheStateBeforeFirstSubscribe() {
        // 不调用 subscribe* 时不应建立任何 socket，state 维持 DISCONNECTED。
        // 用一个本地不存在的 endpoint 也不会 fail，因为还没 connect。
        try (ClobWebSocketClient c = ClobWebSocketClient.create(
                URI.create("ws://127.0.0.1:1"), WebSocketConfig.defaults())) {
            assertThat(c.marketConnectionState()).isEqualTo(ConnectionState.DISCONNECTED);
            assertThat(c.subscriptionCount()).isZero();
            assertThat(c.baseEndpoint()).isEqualTo(URI.create("ws://127.0.0.1:1"));
            assertThat(c.marketEndpoint()).isEqualTo(URI.create("ws://127.0.0.1:1/ws/market"));
            assertThat(c.userEndpoint()).isEqualTo(URI.create("ws://127.0.0.1:1/ws/user"));
        }
    }

    @Test
    void subscribeOrderbookValidatesInputs() {
        try (ClobWebSocketClient c = ClobWebSocketClient.create(
                URI.create("ws://127.0.0.1:1"), WebSocketConfig.defaults())) {
            SubscriptionListener<BookUpdate> noop = msg -> { };

            assertThatNullPointerException().isThrownBy(
                    () -> c.subscribeOrderbook(null, noop));
            assertThatThrownBy(() -> c.subscribeOrderbook(List.of(), noop))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("assetIds");

            // listener 为空：register() 会 NPE，与 record 校验路径一致。
            assertThatNullPointerException().isThrownBy(
                    () -> c.subscribeOrderbook(List.of(ASSET), null));
        }
    }

    @Test
    void subscribePriceChangesAndOthersAlsoValidate() {
        try (ClobWebSocketClient c = ClobWebSocketClient.create(
                URI.create("ws://127.0.0.1:1"), WebSocketConfig.defaults())) {
            assertThatThrownBy(() -> c.subscribePriceChanges(List.of(), msg -> { }))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> c.subscribeTickSizeChanges(List.of(), msg -> { }))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> c.subscribeLastTradePrices(List.of(), msg -> { }))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void authenticatedSubscribeOrdersValidatesMarkets() {
        try (AuthenticatedClobWebSocketClient c = AuthenticatedClobWebSocketClient.create(
                URI.create("ws://127.0.0.1:1"), WebSocketConfig.defaults(), CREDS)) {
            assertThat(c.userConnectionState()).isEqualTo(ConnectionState.DISCONNECTED);
            assertThat(c.credentials()).isSameAs(CREDS);

            SubscriptionListener<OrderMessage> noop = msg -> { };
            assertThatThrownBy(() -> c.subscribeOrders(List.of(), noop))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("markets");
            assertThatNullPointerException().isThrownBy(
                    () -> c.subscribeOrders(null, noop));
        }
    }

    @Test
    void builderShortcutToAuthenticated() {
        URI endpoint = URI.create("ws://127.0.0.1:1");
        try (AuthenticatedClobWebSocketClient c = ClobWebSocketClient.builder()
                .endpoint(endpoint)
                .config(WebSocketConfig.builder().connectTimeout(Duration.ofSeconds(2)).build())
                .authenticated(CREDS)) {
            assertThat(c.baseEndpoint()).isEqualTo(endpoint);
            assertThat(c.credentials()).isSameAs(CREDS);
        }
    }

    @Test
    void channelGatewayUserChannelRequiresCredentials() {
        // 直接构造 ChannelGateway USER 时缺凭证应当立刻抛错。
        // 走包内入口是 AuthenticatedClobWebSocketClient，已强制注入；这里用反射不优雅，
        // 直接用 ChannelGateway 包内构造器（同包测试可见）。
        assertThatThrownBy(() -> new ChannelGateway(
                Channel.USER,
                URI.create("ws://127.0.0.1:1/ws/user"),
                WebSocketConfig.defaults(),
                com.polymarket.clob.http.JsonCodec.objectMapper(),
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credentials");
    }

    @Test
    void subscriptionCancelIsIdempotent() {
        // 用一个无害的 client 触发 register；我们不需要真连接，只验证 cancel 重复调用安全。
        try (ClobWebSocketClient c = ClobWebSocketClient.create(
                URI.create("ws://127.0.0.1:1"), WebSocketConfig.defaults())) {
            Subscription sub = c.subscribeOrderbook(List.of(ASSET), msg -> { });
            assertThat(sub.isActive()).isTrue();

            sub.cancel();
            assertThat(sub.isActive()).isFalse();

            // 再次 cancel 不应抛错也不会反复触发底层 unsubscribe。
            sub.cancel();
            assertThat(sub.isActive()).isFalse();
        }
    }

    @Test
    void clobWebSocketClientCloseIsIdempotent() {
        ClobWebSocketClient c = ClobWebSocketClient.create(
                URI.create("ws://127.0.0.1:1"), WebSocketConfig.defaults());
        c.close();
        c.close(); // 再次关闭不应抛错
        assertThat(c.subscriptionCount()).isZero();
    }

    @Test
    void authenticatedCloseAlsoTearsDownMarketChannel() {
        AuthenticatedClobWebSocketClient c = AuthenticatedClobWebSocketClient.create(
                URI.create("ws://127.0.0.1:1"), WebSocketConfig.defaults(), CREDS);
        c.close();
        c.close();
        assertThat(c.marketConnectionState()).isEqualTo(ConnectionState.DISCONNECTED);
        assertThat(c.userConnectionState()).isEqualTo(ConnectionState.DISCONNECTED);
    }

    @Test
    void builderRequiresValidInputs() {
        assertThatNullPointerException().isThrownBy(
                () -> ClobWebSocketClient.builder().endpoint(null));
        assertThatNullPointerException().isThrownBy(
                () -> ClobWebSocketClient.builder().config(null));
    }

    @Test
    void subscribeAllUserEventsRoutesBothOrderAndTrade() {
        try (AuthenticatedClobWebSocketClient c = AuthenticatedClobWebSocketClient.create(
                URI.create("ws://127.0.0.1:1"), WebSocketConfig.defaults(), CREDS)) {
            Subscription sub = c.subscribeAllUserEvents(List.of(MARKET), msg -> { });
            assertThat(sub).isNotNull();
            assertThat(sub.channel()).isEqualTo(Channel.USER);
            assertThat(sub.markets()).containsExactly(MARKET);
            assertThat(sub.isActive()).isTrue();
        }
    }
}
