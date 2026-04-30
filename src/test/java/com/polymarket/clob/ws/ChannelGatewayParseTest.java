package com.polymarket.clob.ws;

import com.polymarket.clob.http.JsonCodec;
import com.polymarket.clob.model.Hash32;
import com.polymarket.clob.ws.message.BookUpdate;
import com.polymarket.clob.ws.message.PriceChange;
import com.polymarket.clob.ws.message.WsMessage;
import com.polymarket.clob.ws.request.Channel;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 直接拿 {@link ChannelGateway#onText(String)} 测 wire 解析路径，不依赖真 socket。
 *
 * <p>覆盖与 Rust {@code parse_if_interested} 对齐的三类形态：</p>
 * <ul>
 *   <li>单 obj：正常派发；</li>
 *   <li>顶层数组：逐条派发 / 静默跳过未知元素；</li>
 *   <li>缺 {@code event_type} / 未知 {@code event_type}：静默跳过，不当 error。</li>
 * </ul>
 */
class ChannelGatewayParseTest {

    private static final BigInteger ASSET_A = new BigInteger("12345");
    private static final BigInteger ASSET_B = new BigInteger("67890");
    private static final Hash32 MARKET_A = Hash32.fromHex(
            "0x0000000000000000000000000000000000000000000000000000000000000001");

    /** Channel 不会真去连——我们不调 register，仅注入一个 sub 到 set 测路由。 */
    private ChannelGateway newMarketGateway() {
        return new ChannelGateway(Channel.MARKET,
                URI.create("ws://127.0.0.1:1/ws/market"),
                WebSocketConfig.defaults(),
                JsonCodec.objectMapper(),
                null);
    }

    private static <T extends WsMessage> Subscription mkSub(
            Channel ch, java.util.function.Predicate<WsMessage> matcher,
            SubscriptionListener<T> listener) {
        return new Subscription(ch, matcher, listener,
                List.of(ASSET_A), List.of(MARKET_A), () -> { });
    }

    @Test
    void singleObjectIsRouted() throws Exception {
        ChannelGateway g = newMarketGateway();
        try {
            List<BookUpdate> got = new ArrayList<>();
            Subscription sub = mkSub(Channel.MARKET,
                    msg -> msg instanceof BookUpdate b && b.assetId().equals(ASSET_A),
                    (SubscriptionListener<BookUpdate>) got::add);
            injectSubscription(g, sub);

            String text = """
                    {"event_type":"book","asset_id":"%s","market":"%s","timestamp":"1"}
                    """.formatted(ASSET_A, MARKET_A.toHex());
            g.onText(text);

            assertThat(got).hasSize(1);
            assertThat(got.get(0).assetId()).isEqualTo(ASSET_A);
        } finally {
            g.close();
        }
    }

    @Test
    void arrayPayloadDispatchesEachElement() throws Exception {
        ChannelGateway g = newMarketGateway();
        try {
            AtomicInteger bookCount = new AtomicInteger();
            AtomicInteger priceCount = new AtomicInteger();

            Subscription bookSub = mkSub(Channel.MARKET,
                    msg -> msg instanceof BookUpdate,
                    (SubscriptionListener<BookUpdate>) m -> bookCount.incrementAndGet());
            Subscription priceSub = mkSub(Channel.MARKET,
                    msg -> msg instanceof PriceChange,
                    (SubscriptionListener<PriceChange>) m -> priceCount.incrementAndGet());

            injectSubscription(g, bookSub);
            injectSubscription(g, priceSub);

            String text = """
                    [
                      {"event_type":"book","asset_id":"%s","market":"%s","timestamp":"1"},
                      {"event_type":"price_change","market":"%s","timestamp":"2","price_changes":[]},
                      {"event_type":"book","asset_id":"%s","market":"%s","timestamp":"3"}
                    ]
                    """.formatted(ASSET_A, MARKET_A.toHex(),
                                  MARKET_A.toHex(),
                                  ASSET_B, MARKET_A.toHex());

            g.onText(text);

            assertThat(bookCount.get()).isEqualTo(2);
            assertThat(priceCount.get()).isEqualTo(1);
        } finally {
            g.close();
        }
    }

    @Test
    void unknownEventTypeIsSilentlySkipped() throws Exception {
        ChannelGateway g = newMarketGateway();
        try {
            AtomicInteger errorCount = new AtomicInteger();
            AtomicInteger msgCount = new AtomicInteger();

            Subscription sub = mkSub(Channel.MARKET, msg -> true, new SubscriptionListener<WsMessage>() {
                @Override public void onMessage(WsMessage message) { msgCount.incrementAndGet(); }
                @Override public void onError(Throwable error) { errorCount.incrementAndGet(); }
            });
            injectSubscription(g, sub);

            // Polymarket 灰度新事件：event_type=brand_new 无对应 permit
            String text = "{\"event_type\":\"brand_new\",\"foo\":\"bar\"}";
            g.onText(text);

            assertThat(msgCount.get()).isZero();
            assertThat(errorCount.get())
                    .as("未知 event_type 应静默 skip，不应当作 error 派发")
                    .isZero();
        } finally {
            g.close();
        }
    }

    @Test
    void missingEventTypeIsSilentlySkipped() throws Exception {
        ChannelGateway g = newMarketGateway();
        try {
            AtomicInteger errorCount = new AtomicInteger();
            Subscription sub = mkSub(Channel.MARKET, msg -> true, new SubscriptionListener<WsMessage>() {
                @Override public void onMessage(WsMessage message) { /* unused */ }
                @Override public void onError(Throwable error) { errorCount.incrementAndGet(); }
            });
            injectSubscription(g, sub);

            // 没有 event_type
            g.onText("{\"foo\":42}");
            // event_type 不是 string
            g.onText("{\"event_type\":123}");

            assertThat(errorCount.get()).isZero();
        } finally {
            g.close();
        }
    }

    @Test
    void invalidJsonDeliversErrorToAllSubs() throws Exception {
        ChannelGateway g = newMarketGateway();
        try {
            AtomicInteger errCount = new AtomicInteger();
            Subscription sub = mkSub(Channel.MARKET, msg -> true, new SubscriptionListener<WsMessage>() {
                @Override public void onMessage(WsMessage message) { /* unused */ }
                @Override public void onError(Throwable error) { errCount.incrementAndGet(); }
            });
            injectSubscription(g, sub);

            g.onText("not-json-at-all");

            assertThat(errCount.get())
                    .as("非法 JSON 顶层应当通过 deliverError 通知所有订阅")
                    .isEqualTo(1);
        } finally {
            g.close();
        }
    }

    @Test
    void closedGatewayRejectsRegister() {
        ChannelGateway g = newMarketGateway();
        g.close();
        assertThatThrownBy(() -> g.register(
                msg -> true,
                (SubscriptionListener<BookUpdate>) m -> { },
                List.of(ASSET_A),
                List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void closedGatewayMarksExistingSubscriptionsInactive() throws Exception {
        ChannelGateway g = newMarketGateway();
        Subscription sub = mkSub(Channel.MARKET, msg -> true,
                (SubscriptionListener<WsMessage>) m -> { });
        injectSubscription(g, sub);

        assertThat(sub.isActive()).isTrue();
        g.close();
        assertThat(sub.isActive())
                .as("close() 应把 in-flight 订阅置为非 active，避免 stale 派发")
                .isFalse();
    }

    /** 用反射往 ChannelGateway 的 subscriptions set 注入测试用 sub，不触发 ensureConnection。 */
    private static void injectSubscription(ChannelGateway g, Subscription sub) throws Exception {
        java.lang.reflect.Field f = ChannelGateway.class.getDeclaredField("subscriptions");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Set<Subscription> set = (java.util.Set<Subscription>) f.get(g);
        set.add(sub);
    }
}
