package com.polymarket.clob.ws;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 不需要真起 socket 的 {@link WsConnection} 边界路径单测。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>构造参数 null 校验；</li>
 *   <li>{@link WsConnection#sendText(String)} 在 DISCONNECTED / CLOSED 状态立即失败；</li>
 *   <li>{@link WsConnection#close()} 幂等（多次调用安全）；</li>
 *   <li>已 CLOSED 后再 connect 立即失败；</li>
 *   <li>第一次 {@link WsConnection#connect()} 对一个不存在的 endpoint：会触发 onError + 状态进入重连。</li>
 * </ul>
 */
class WsConnectionTest {

    private static final URI FAKE = URI.create("ws://127.0.0.1:1");
    private static final MessageHandler NOOP = new MessageHandler() {
        @Override public void onText(String text) { }
    };

    private static WebSocketConfig boundedConfig() {
        return WebSocketConfig.builder()
                .initialBackoff(Duration.ofMillis(20))
                .maxBackoff(Duration.ofMillis(40))
                .connectTimeout(Duration.ofSeconds(1))
                .maxReconnectAttempts(0)
                .build();
    }

    @Test
    void rejectsNullArgs() {
        assertThatNullPointerException().isThrownBy(
                () -> new WsConnection(null, WebSocketConfig.defaults(), NOOP));
        assertThatNullPointerException().isThrownBy(
                () -> new WsConnection(FAKE, null, NOOP));
        assertThatNullPointerException().isThrownBy(
                () -> new WsConnection(FAKE, WebSocketConfig.defaults(), null));
    }

    @Test
    void sendTextOnDisconnectedFailsImmediately() {
        WsConnection c = new WsConnection(FAKE, WebSocketConfig.defaults(), NOOP);
        try {
            CompletableFuture<Void> f = c.sendText("hi");
            assertThat(f).isCompletedExceptionally();
            assertThatThrownBy(f::join)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DISCONNECTED");
        } finally {
            c.close();
        }
    }

    @Test
    void sendTextOnClosedFailsImmediately() {
        WsConnection c = new WsConnection(FAKE, WebSocketConfig.defaults(), NOOP);
        c.close();
        CompletableFuture<Void> f = c.sendText("hi");
        assertThat(f).isCompletedExceptionally();
        assertThatThrownBy(f::join)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLOSED");
    }

    @Test
    void closeIsIdempotent() {
        WsConnection c = new WsConnection(FAKE, WebSocketConfig.defaults(), NOOP);
        c.close();
        c.close();
        c.close();
        assertThat(c.state()).isEqualTo(ConnectionState.CLOSED);
    }

    @Test
    void connectAfterCloseFailsImmediately() {
        WsConnection c = new WsConnection(FAKE, WebSocketConfig.defaults(), NOOP);
        c.close();
        CompletableFuture<Void> f = c.connect();
        assertThat(f).isCompletedExceptionally();
        assertThatThrownBy(f::join)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void initialConnectFailureFiresHandlerOnErrorAndRespectsMaxAttempts()
            throws InterruptedException {
        // 用 maxReconnectAttempts=0 + 极端短 timeout，连接到不存在的端口：
        // doBuildSocket 返回 failedFuture → connect future 失败 → handler.onError 触发，
        // 然后 scheduleReconnect 因 next > 0 直接转回 DISCONNECTED 不再重连。
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicInteger states = new AtomicInteger(0);
        MessageHandler handler = new MessageHandler() {
            @Override public void onText(String text) { }
            @Override public void onError(Throwable error) { errorCount.incrementAndGet(); }
        };
        WsConnection c = new WsConnection(FAKE, boundedConfig(), handler);
        c.addStateListener(s -> states.incrementAndGet());
        try {
            CompletableFuture<Void> f = c.connect();
            // 等 connect future 完成（成功或失败均可，但 1 端口下应失败）
            try {
                f.get(3, TimeUnit.SECONDS);
            } catch (ExecutionException | java.util.concurrent.TimeoutException ignored) {
                // expected: 连接失败或超时
            }
            for (int i = 0; i < 20 && c.state() != ConnectionState.DISCONNECTED; i++) {
                Thread.sleep(50);
            }
            assertThat(errorCount.get()).isGreaterThanOrEqualTo(1);
            assertThat(c.state()).isEqualTo(ConnectionState.DISCONNECTED);
            assertThat(states.get()).isGreaterThanOrEqualTo(1);
        } finally {
            c.close();
        }
    }
}
