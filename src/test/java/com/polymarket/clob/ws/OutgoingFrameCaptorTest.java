package com.polymarket.clob.ws;

import com.polymarket.clob.ws.message.BookUpdate;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 {@link OutgoingFrameCaptor} 注入：发起一次 market 订阅时，captor 应该
 * 在 WS 出帧前同步收到一条与 wire 完全一致的 JSON frame。
 *
 * <p>测试用嵌入式 {@code Java-WebSocket} echo server 模拟 endpoint，避免真实
 * 网络依赖；只关心 client → server 方向的出帧内容。</p>
 */
class OutgoingFrameCaptorTest {

    private WebSocketServer echo;
    private int port;

    @BeforeEach
    void start() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        echo = new WebSocketServer(new InetSocketAddress("127.0.0.1", 0)) {
            @Override public void onOpen(WebSocket conn, ClientHandshake hs) {}
            @Override public void onClose(WebSocket conn, int c, String r, boolean rb) {}
            @Override public void onMessage(WebSocket conn, String msg) { /* discard */ }
            @Override public void onError(WebSocket conn, Exception e) {}
            @Override public void onStart() { ready.countDown(); }
        };
        echo.setReuseAddr(true);
        echo.start();
        ready.await(2, TimeUnit.SECONDS);
        port = echo.getPort();
    }

    @AfterEach
    void stop() throws Exception {
        if (echo != null) echo.stop(1000);
    }

    @Test
    void marketSubscribeFrameCaptured() throws InterruptedException {
        List<String> frames = new ArrayList<>();
        OutgoingFrameCaptor captor = (channel, uri, body) -> frames.add(body);

        ClobWebSocketClient ws = ClobWebSocketClient.builder()
                .endpoint(URI.create("ws://127.0.0.1:" + port))
                .outgoingFrameCaptor(captor)
                .build();

        try {
            ws.subscribeOrderbook(List.of(BigInteger.valueOf(123)),
                    new SubscriptionListener<BookUpdate>() {
                        @Override public void onMessage(BookUpdate m) { }
                    });
            // 等 300ms 让握手 + sendText 真正落到 captor
            Thread.sleep(300);
        } finally {
            ws.close();
        }

        assertThat(frames).hasSize(1);
        assertThat(frames.get(0))
                .contains("\"type\":\"market\"")
                .contains("\"operation\":\"subscribe\"")
                .contains("\"assets_ids\":[\"123\"]");
    }
}
