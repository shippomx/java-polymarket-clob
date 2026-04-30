package com.polymarket.clob.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.http.JsonCodec;
import com.polymarket.clob.model.Hash32;
import com.polymarket.clob.ws.message.BookUpdate;
import com.polymarket.clob.ws.message.OrderMessage;
import com.polymarket.clob.ws.message.PriceChange;
import com.polymarket.clob.ws.test.EmbeddedWsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebSocket 子系统集成测试：跑在嵌入式 Java-WebSocket 服务器上，验证
 * subscribe 报文格式、消息派发与 user channel auth 注入、以及断线 → 重连 → 重放
 * 订阅的端到端行为。
 *
 * <p>这些场景在纯单测里没法覆盖：</p>
 * <ul>
 *   <li>JDK {@link java.net.http.WebSocket} 真的握手 + 发帧 + 接帧；</li>
 *   <li>{@link WsConnection} 的指数退避真的会触发；</li>
 *   <li>{@link ChannelGateway} 在 reconnect 后重新 subscribe；</li>
 *   <li>认证 envelope 的 auth 三元组真在线传到 server 端。</li>
 * </ul>
 *
 * <p>所有 timeout 都偏向"短而显式"：默认 5s，重连场景把 initialBackoff 调到 50ms
 * 加快收敛。</p>
 */
class WsIntegrationTest {

    private static final ObjectMapper MAPPER = JsonCodec.objectMapper();

    private static final BigInteger ASSET_A =
            new BigInteger("106585164761922456203746651621390029417453862034640469075081961934906147433548");
    private static final BigInteger ASSET_B =
            new BigInteger("987654321098765432109876543210987654321098765432109876543210987654321098765");

    private static final Hash32 MARKET_A = Hash32.fromHex(
            "0x0000000000000000000000000000000000000000000000000000000000000001");
    private static final Hash32 MARKET_B = Hash32.fromHex(
            "0x0000000000000000000000000000000000000000000000000000000000000002");

    private static final ApiCredentials CREDS = new ApiCredentials(
            "00000000-0000-0000-0000-000000000000",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "passphrase-value");

    private EmbeddedWsServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = EmbeddedWsServer.launch();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
    }

    // ----------------- helpers -----------------

    private WebSocketConfig fastReconnectConfig() {
        return WebSocketConfig.builder()
                .initialBackoff(Duration.ofMillis(50))
                .maxBackoff(Duration.ofMillis(200))
                .backoffMultiplier(2.0)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    /** 收到的消息走这个 queue 跨线程交付到测试方法。 */
    private static <T> BlockingQueue<T> queue() {
        return new ArrayBlockingQueue<>(64);
    }

    private static <T extends com.polymarket.clob.ws.message.WsMessage> SubscriptionListener<T>
            queueListener(BlockingQueue<T> q) {
        return new SubscriptionListener<>() {
            @Override public void onMessage(T message) { q.add(message); }
        };
    }

    // ----------------- test cases -----------------

    @Test
    void marketSubscribeEnvelopeMatchesWireFormat() throws Exception {
        try (ClobWebSocketClient client = ClobWebSocketClient.create(server.baseUri(), fastReconnectConfig())) {
            BlockingQueue<BookUpdate> received = queue();
            Subscription sub = client.subscribeOrderbook(List.of(ASSET_A), queueListener(received));
            assertThat(sub).isNotNull();
            assertThat(sub.isActive()).isTrue();

            String envelope = server.awaitMessage("/ws/market", 5_000);
            JsonNode node = MAPPER.readTree(envelope);
            assertThat(node.get("type").asText()).isEqualTo("market");
            assertThat(node.get("operation").asText()).isEqualTo("subscribe");
            assertThat(node.get("assets_ids").get(0).isTextual()).isTrue();
            assertThat(node.get("assets_ids").get(0).asText()).isEqualTo(ASSET_A.toString());
            assertThat(node.get("initial_dump").asBoolean()).isTrue();
            assertThat(node.has("auth")).isFalse(); // 未认证态不写 auth
        }
    }

    @Test
    void serverPushedBookUpdateDispatchedToListener() throws Exception {
        try (ClobWebSocketClient client = ClobWebSocketClient.create(server.baseUri(), fastReconnectConfig())) {
            BlockingQueue<BookUpdate> received = queue();
            client.subscribeOrderbook(List.of(ASSET_A), queueListener(received));

            // 先确认 server 已收到 subscribe envelope（也作为同步点：此时 client 已 CONNECTED）
            server.awaitMessage("/ws/market", 5_000);

            String push = """
                    {
                      "event_type":"book",
                      "asset_id":"%s",
                      "market":"%s",
                      "timestamp":"1700000000",
                      "bids":[{"price":"0.5","size":"100"}],
                      "asks":[{"price":"0.51","size":"50"}],
                      "hash":"abcdef"
                    }
                    """.formatted(ASSET_A, MARKET_A.toHex());
            server.broadcast("/ws/market", push);

            BookUpdate book = received.poll(5, TimeUnit.SECONDS);
            assertThat(book).isNotNull();
            assertThat(book.assetId()).isEqualTo(ASSET_A);
            assertThat(book.timestamp()).isEqualTo(1_700_000_000L);
            assertThat(book.bids()).hasSize(1);
            assertThat(book.bids().get(0).price()).isEqualByComparingTo("0.5");
            assertThat(book.hash()).isEqualTo("abcdef");
        }
    }

    @Test
    void unsubscribedAssetIsFilteredOut() throws Exception {
        try (ClobWebSocketClient client = ClobWebSocketClient.create(server.baseUri(), fastReconnectConfig())) {
            BlockingQueue<BookUpdate> received = queue();
            client.subscribeOrderbook(List.of(ASSET_A), queueListener(received));

            server.awaitMessage("/ws/market", 5_000);

            // 服务端推送另一个 asset 的 book，listener 不应被触发。
            String push = """
                    {"event_type":"book","asset_id":"%s","market":"%s","timestamp":"1"}
                    """.formatted(ASSET_B, MARKET_A.toHex());
            server.broadcast("/ws/market", push);

            BookUpdate book = received.poll(500, TimeUnit.MILLISECONDS);
            assertThat(book).isNull();
        }
    }

    @Test
    void priceChangeBatchDispatchesIfAnyAssetMatches() throws Exception {
        try (ClobWebSocketClient client = ClobWebSocketClient.create(server.baseUri(), fastReconnectConfig())) {
            BlockingQueue<PriceChange> received = queue();
            client.subscribePriceChanges(List.of(ASSET_A), queueListener(received));
            server.awaitMessage("/ws/market", 5_000);

            String push = """
                    {
                      "event_type":"price_change",
                      "market":"%s",
                      "timestamp":"1700000123",
                      "price_changes":[
                        {"asset_id":"%s","price":"0.10","side":"BUY"},
                        {"asset_id":"%s","price":"0.20","side":"SELL"}
                      ]
                    }
                    """.formatted(MARKET_A.toHex(), ASSET_B, ASSET_A);
            server.broadcast("/ws/market", push);

            PriceChange pc = received.poll(5, TimeUnit.SECONDS);
            assertThat(pc).isNotNull();
            assertThat(pc.priceChanges()).hasSize(2);
            assertThat(pc.timestamp()).isEqualTo(1_700_000_123L);
        }
    }

    @Test
    void cancelSendsUnsubscribeEnvelope() throws Exception {
        try (ClobWebSocketClient client = ClobWebSocketClient.create(server.baseUri(), fastReconnectConfig())) {
            BlockingQueue<BookUpdate> received = queue();
            Subscription sub = client.subscribeOrderbook(List.of(ASSET_A), queueListener(received));

            // 第一帧：subscribe envelope
            String first = server.awaitMessage("/ws/market", 5_000);
            assertThat(first).contains("\"operation\":\"subscribe\"");

            sub.cancel();
            assertThat(sub.isActive()).isFalse();

            // 第二帧：unsubscribe envelope
            String second = server.awaitMessage("/ws/market", 5_000);
            JsonNode node = MAPPER.readTree(second);
            assertThat(node.get("type").asText()).isEqualTo("market");
            assertThat(node.get("operation").asText()).isEqualTo("unsubscribe");
            assertThat(node.get("assets_ids").get(0).asText()).isEqualTo(ASSET_A.toString());
            assertThat(node.has("initial_dump")).isFalse();
        }
    }

    @Test
    void reconnectReplaysSubscriptions() throws Exception {
        try (ClobWebSocketClient client = ClobWebSocketClient.create(server.baseUri(), fastReconnectConfig())) {
            BlockingQueue<BookUpdate> received = queue();
            client.subscribeOrderbook(List.of(ASSET_A), queueListener(received));

            // 1) 初次 subscribe envelope。
            server.awaitMessage("/ws/market", 5_000);
            assertThat(server.openConnections("/ws/market")).isEqualTo(1);

            // 2) 服务端主动断开当前连接，触发客户端重连。
            server.closeAll(1006); // ABNORMAL_CLOSURE 风格

            // 3) 重连后客户端应自动再次发 subscribe envelope。
            String replayed = server.awaitMessage("/ws/market", 5_000);
            JsonNode node = MAPPER.readTree(replayed);
            assertThat(node.get("operation").asText()).isEqualTo("subscribe");
            assertThat(node.get("assets_ids").get(0).asText()).isEqualTo(ASSET_A.toString());

            // 4) 重连后第一推消息也应能正常派发。
            String push = """
                    {"event_type":"book","asset_id":"%s","market":"%s","timestamp":"2"}
                    """.formatted(ASSET_A, MARKET_A.toHex());
            server.broadcast("/ws/market", push);

            BookUpdate book = received.poll(5, TimeUnit.SECONDS);
            assertThat(book).isNotNull();
            assertThat(book.assetId()).isEqualTo(ASSET_A);
        }
    }

    @Test
    void userSubscribeIncludesAuthEnvelope() throws Exception {
        try (AuthenticatedClobWebSocketClient client = AuthenticatedClobWebSocketClient.create(
                server.baseUri(), fastReconnectConfig(), CREDS)) {

            BlockingQueue<OrderMessage> orders = queue();
            client.subscribeOrders(List.of(MARKET_A), queueListener(orders));

            String envelope = server.awaitMessage("/ws/user", 5_000);
            JsonNode node = MAPPER.readTree(envelope);
            assertThat(node.get("type").asText()).isEqualTo("user");
            assertThat(node.get("operation").asText()).isEqualTo("subscribe");
            assertThat(node.get("markets").get(0).asText()).isEqualTo(MARKET_A.toHex());

            JsonNode auth = node.get("auth");
            assertThat(auth).isNotNull();
            assertThat(auth.get("apiKey").asText()).isEqualTo(CREDS.apiKey());
            assertThat(auth.get("secret").asText()).isEqualTo(CREDS.secret());
            assertThat(auth.get("passphrase").asText()).isEqualTo(CREDS.passphrase());
        }
    }

    @Test
    void userOrderPushDispatchedToListener() throws Exception {
        try (AuthenticatedClobWebSocketClient client = AuthenticatedClobWebSocketClient.create(
                server.baseUri(), fastReconnectConfig(), CREDS)) {

            BlockingQueue<OrderMessage> orders = queue();
            client.subscribeOrders(List.of(MARKET_A), queueListener(orders));
            server.awaitMessage("/ws/user", 5_000);

            String push = """
                    {
                      "event_type":"order",
                      "id":"ord-1",
                      "market":"%s",
                      "asset_id":"%s",
                      "side":"BUY",
                      "price":"0.5",
                      "type":"PLACEMENT",
                      "outcome":"YES",
                      "owner":"00000000-0000-0000-0000-000000000000",
                      "order_owner":"00000000-0000-0000-0000-000000000000",
                      "original_size":"10",
                      "size_matched":"0",
                      "timestamp":"1700000100",
                      "associate_trades":[],
                      "status":"LIVE"
                    }
                    """.formatted(MARKET_A.toHex(), ASSET_A);
            server.broadcast("/ws/user", push);

            OrderMessage ord = orders.poll(5, TimeUnit.SECONDS);
            assertThat(ord).isNotNull();
            assertThat(ord.id()).isEqualTo("ord-1");
            assertThat(ord.market()).isEqualTo(MARKET_A);
            assertThat(ord.status()).isEqualTo("LIVE");
        }
    }

    @Test
    void marketAndUserChannelsUseSeparateConnections() throws Exception {
        try (AuthenticatedClobWebSocketClient client = AuthenticatedClobWebSocketClient.create(
                server.baseUri(), fastReconnectConfig(), CREDS)) {

            BlockingQueue<BookUpdate> books = queue();
            BlockingQueue<OrderMessage> orders = queue();
            client.subscribeOrderbook(List.of(ASSET_A), queueListener(books));
            client.subscribeOrders(List.of(MARKET_A), queueListener(orders));

            // 两个 channel 分别都收到 envelope —— 表示底层确实建了两条 connection。
            server.awaitMessage("/ws/market", 5_000);
            server.awaitMessage("/ws/user", 5_000);
            assertThat(server.openConnections("/ws/market")).isEqualTo(1);
            assertThat(server.openConnections("/ws/user")).isEqualTo(1);

            // close() 应同时下掉两条 connection。
            client.close();

            // server 端 onClose 异步：放宽到 2s 让连接计数归零。
            assertThat(eventually(2_000, () ->
                    server.openConnections("/ws/market") == 0
                            && server.openConnections("/ws/user") == 0))
                    .as("both server-side connections should close within 2s")
                    .isTrue();
        }
    }

    /**
     * 简易 polling util：在 {@code timeoutMs} 内每 25ms 检查一次条件，命中即返回 true。
     * 避免引入 awaitility 这一个外部依赖。
     */
    private static boolean eventually(long timeoutMs, BooleanSupplier cond) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        do {
            if (cond.getAsBoolean()) return true;
            try { Thread.sleep(25); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (System.nanoTime() < deadline);
        return cond.getAsBoolean();
    }

    @Test
    void multipleSubscribersOnSameChannelShareConnection() throws Exception {
        try (ClobWebSocketClient client = ClobWebSocketClient.create(server.baseUri(), fastReconnectConfig())) {
            BlockingQueue<BookUpdate> q1 = queue();
            BlockingQueue<BookUpdate> q2 = queue();
            AtomicInteger errCount = new AtomicInteger(0);

            client.subscribeOrderbook(List.of(ASSET_A), queueListener(q1));
            // 先消费第一帧 subscribe envelope，再发起第二个订阅，避免顺序不稳定
            server.awaitMessage("/ws/market", 5_000);

            client.subscribeOrderbook(List.of(ASSET_A), new SubscriptionListener<BookUpdate>() {
                @Override public void onMessage(BookUpdate message) { q2.add(message); }
                @Override public void onError(Throwable error) { errCount.incrementAndGet(); }
            });
            server.awaitMessage("/ws/market", 5_000);

            assertThat(server.openConnections("/ws/market")).isEqualTo(1);

            String push = """
                    {"event_type":"book","asset_id":"%s","market":"%s","timestamp":"3"}
                    """.formatted(ASSET_A, MARKET_A.toHex());
            server.broadcast("/ws/market", push);

            assertThat(q1.poll(5, TimeUnit.SECONDS)).isNotNull();
            assertThat(q2.poll(5, TimeUnit.SECONDS)).isNotNull();
            assertThat(errCount.get()).isZero();
        }
    }
}
