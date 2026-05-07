package com.polymarket.clob.example;

import com.polymarket.clob.AuthenticatedClobClient;
import com.polymarket.clob.ClobClient;
import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.model.ChainId;
import com.polymarket.clob.model.Hash32;
import com.polymarket.clob.ws.AuthenticatedClobWebSocketClient;
import com.polymarket.clob.ws.ClobWebSocketClient;
import com.polymarket.clob.ws.Subscription;
import com.polymarket.clob.ws.SubscriptionListener;
import com.polymarket.clob.ws.WebSocketConfig;
import com.polymarket.clob.ws.message.BookUpdate;
import com.polymarket.clob.ws.message.LastTradePrice;
import com.polymarket.clob.ws.message.OrderMessage;
import com.polymarket.clob.ws.message.PriceChange;
import com.polymarket.clob.ws.message.TradeMessage;

import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * WebSocket 订阅示例：演示 market（公开行情）与 user（账户订单/成交）两类订阅。
 *
 * <p>支持两种模式（环境变量 {@code MODE}）：</p>
 * <ul>
 *   <li>{@code market}（默认）：纯只读，仅需 {@code ASSET_IDS}，订阅 BookUpdate /
 *       PriceChange / LastTradePrice；</li>
 *   <li>{@code user}：在 market 之外再开一条 user channel，需要 {@code CLOB_PRIVATE_KEY}
 *       + {@code MARKETS}，监听账户订单 / 成交事件。</li>
 * </ul>
 *
 * <p>环境变量：</p>
 * <ul>
 *   <li>{@code WS_ENDPOINT}：默认 {@code wss://ws-subscriptions-clob.polymarket.com}</li>
 *   <li>{@code ASSET_IDS}：逗号分隔 token id 列表（market 模式必填）</li>
 *   <li>{@code MARKETS}：逗号分隔 condition id（hex）列表（user 模式必填）</li>
 *   <li>{@code DURATION_SECONDS}：示例运行时长，默认 30</li>
 *   <li>{@code MODE}：{@code market}（默认）或 {@code user}</li>
 *   <li>认证模式专用：{@code CLOB_PRIVATE_KEY} / {@code CLOB_ENDPOINT} /
 *       {@code CLOB_CHAIN_ID} / {@code CLOB_SIGNATURE_TYPE}（同 {@code AuthenticatedExample}）。</li>
 * </ul>
 *
 * <p>运行：
 * <pre>{@code
 * # 公开行情
 * ASSET_IDS=12345,67890 mvn -B compile exec:java \
 *     -Dexec.mainClass=com.polymarket.clob.example.WebSocketOrderBookExample
 *
 * # 含 user channel
 * MODE=user MARKETS=0x123...,0x456... \
 *     CLOB_PRIVATE_KEY=0xac0974... DURATION_SECONDS=60 \
 *     mvn -B compile exec:java \
 *         -Dexec.mainClass=com.polymarket.clob.example.WebSocketOrderBookExample
 * }</pre>
 */
public final class WebSocketOrderBookExample {

    private WebSocketOrderBookExample() {}

    public static void main(String[] args) throws Exception {
        String mode = Optional.ofNullable(System.getenv("MODE")).orElse("market").trim().toLowerCase();
        URI wsEndpoint = URI.create(Optional.ofNullable(System.getenv("WS_ENDPOINT"))
                .orElse(ClobWebSocketClient.DEFAULT_ENDPOINT.toString()));
        long durationSeconds = Long.parseLong(
                Optional.ofNullable(System.getenv("DURATION_SECONDS")).orElse("30"));

        WebSocketConfig config = WebSocketConfig.builder()
                .connectTimeout(Duration.ofSeconds(10))
                .initialBackoff(Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(30))
                .build();

        switch (mode) {
            case "market" -> runMarket(wsEndpoint, config, durationSeconds);
            case "user" -> runUser(wsEndpoint, config, durationSeconds);
            default -> throw new IllegalStateException("unknown MODE=" + mode);
        }
    }

    /** 仅订阅公开行情，不需要 L1/L2 凭证。 */
    private static void runMarket(URI endpoint, WebSocketConfig config, long durationSeconds) throws Exception {
        List<BigInteger> assetIds = parseAssetIds(require("ASSET_IDS"));
        System.out.println("WS endpoint = " + endpoint);
        System.out.println("subscribing market channel for " + assetIds.size() + " asset(s)");

        try (ClobWebSocketClient client = ClobWebSocketClient.create(endpoint, config)) {
            Subscription book = client.subscribeOrderbook(assetIds, new BookListener());
            Subscription pc = client.subscribePriceChanges(assetIds, new PriceChangeListener());
            Subscription ltp = client.subscribeLastTradePrices(assetIds, new LastTradePriceListener());

            sleepWithProgress(durationSeconds, "market");

            book.cancel();
            pc.cancel();
            ltp.cancel();
        }
    }

    /** 同时建 market（行情）与 user（订单）两条 channel。 */
    private static void runUser(URI endpoint, WebSocketConfig config, long durationSeconds) throws Exception {
        List<Hash32> markets = parseMarkets(require("MARKETS"));
        List<BigInteger> assetIds = Optional.ofNullable(System.getenv("ASSET_IDS"))
                .map(WebSocketOrderBookExample::parseAssetIds)
                .orElse(List.of());

        // 1) 用 ClobClient + L1 签名拿到 L2 凭证。
        String pk = require("CLOB_PRIVATE_KEY");
        String restEndpoint = Optional.ofNullable(System.getenv("CLOB_ENDPOINT"))
                .orElse("https://clob.polymarket.com");
        long chainId = parseChainId(System.getenv("CLOB_CHAIN_ID"));
        SignatureType sigType = parseSignatureType(System.getenv("CLOB_SIGNATURE_TYPE"));
        Signer signer = LocalSigner.fromPrivateKey(pk);

        try (ClobClient base = ClobClient.builder().endpoint(restEndpoint).chainId(chainId).build()) {
            AuthenticatedClobClient rest = base
                    .authenticate(signer, sigType, BigInteger.ZERO)
                    .join();
            System.out.println("authed funder = " + rest.funder().toHex());

            // 2) 在 REST 的 authenticated 客户端上拿 WebSocket 入口。
            try (AuthenticatedClobWebSocketClient ws = rest.webSocket(endpoint, config)) {
                if (!assetIds.isEmpty()) {
                    ws.subscribeOrderbook(assetIds, new BookListener());
                }
                ws.subscribeOrders(markets, new OrderListener());
                ws.subscribeTrades(markets, new TradeListener());

                sleepWithProgress(durationSeconds, "user");
            }
        }
    }

    // ---------------- listener 们：单纯打印，业务方自行接 ----------------

    private static final class BookListener implements SubscriptionListener<BookUpdate> {
        @Override public void onMessage(BookUpdate msg) {
            System.out.printf("[book ] asset=%s bids=%d asks=%d hash=%s%n",
                    abbreviate(msg.assetId().toString()), msg.bids().size(), msg.asks().size(), msg.hash());
        }
        @Override public void onError(Throwable err) {
            System.err.println("[book ] error: " + err);
        }
    }

    private static final class PriceChangeListener implements SubscriptionListener<PriceChange> {
        @Override public void onMessage(PriceChange msg) {
            System.out.printf("[price] market=%s changes=%d ts=%d%n",
                    msg.market().toHex(), msg.priceChanges().size(), msg.timestamp());
        }
    }

    private static final class LastTradePriceListener implements SubscriptionListener<LastTradePrice> {
        @Override public void onMessage(LastTradePrice msg) {
            System.out.printf("[trade·last] asset=%s price=%s size=%s%n",
                    abbreviate(msg.assetId().toString()), msg.price(), msg.size());
        }
    }

    private static final class OrderListener implements SubscriptionListener<OrderMessage> {
        @Override public void onMessage(OrderMessage msg) {
            System.out.printf("[order] id=%s type=%s status=%s price=%s%n",
                    msg.id(), msg.messageType(), msg.status(), msg.price());
        }
    }

    private static final class TradeListener implements SubscriptionListener<TradeMessage> {
        @Override public void onMessage(TradeMessage msg) {
            System.out.printf("[trade] id=%s side=%s status=%s size=%s price=%s%n",
                    msg.id(), msg.side(), msg.status(), msg.size(), msg.price());
        }
    }

    // ---------------- helpers ----------------

    private static void sleepWithProgress(long seconds, String mode) throws InterruptedException {
        System.out.println("listening for " + seconds + "s on mode=" + mode + " (Ctrl-C to abort)");
        for (long left = seconds; left > 0; left--) {
            Thread.sleep(1_000);
            if (left % 10 == 0 || left <= 5) {
                System.out.println("  ... " + left + "s remaining");
            }
        }
    }

    private static List<BigInteger> parseAssetIds(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(BigInteger::new)
                .toList();
    }

    private static List<Hash32> parseMarkets(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Hash32::fromHex)
                .toList();
    }

    private static String abbreviate(String s) {
        return s.length() > 16 ? s.substring(0, 8) + "…" + s.substring(s.length() - 4) : s;
    }

    private static String require(String env) {
        String v = System.getenv(env);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("环境变量 " + env + " 未设置");
        }
        return v;
    }

    private static long parseChainId(String raw) {
        if (raw == null || raw.isBlank() || "POLYGON".equalsIgnoreCase(raw)) return ChainId.POLYGON;
        if ("AMOY".equalsIgnoreCase(raw)) return ChainId.AMOY;
        return Long.parseLong(raw);
    }

    private static SignatureType parseSignatureType(String raw) {
        if (raw == null || raw.isBlank()) return SignatureType.EOA;
        return SignatureType.valueOf(raw.trim().toUpperCase());
    }
}
