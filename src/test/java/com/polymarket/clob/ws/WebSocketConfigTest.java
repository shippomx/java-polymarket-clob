package com.polymarket.clob.ws;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link WebSocketConfig} 的纯函数测试：默认值、退避计算、边界 illegal arg。
 *
 * <p>具体连接 / 重连行为放到 Phase 5 的嵌入式 server 集成测试里。</p>
 */
class WebSocketConfigTest {

    @Test
    void defaultsAlignWithRustReconnectConfig() {
        WebSocketConfig cfg = WebSocketConfig.defaults();
        assertThat(cfg.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(cfg.initialBackoff()).isEqualTo(Duration.ofSeconds(1));
        assertThat(cfg.maxBackoff()).isEqualTo(Duration.ofSeconds(60));
        assertThat(cfg.backoffMultiplier()).isEqualTo(2.0);
        assertThat(cfg.maxReconnectAttempts()).isEmpty(); // null = infinite
        assertThat(cfg.heartbeatInterval()).isNull();      // 首版心跳 disabled
        assertThat(cfg.httpClient()).isNotNull();
        assertThat(cfg.listenerExecutor()).isNotNull();
    }

    @Test
    void backoffSequenceCappedAtMax() {
        WebSocketConfig cfg = WebSocketConfig.builder()
                .initialBackoff(Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(8))
                .backoffMultiplier(2.0)
                .build();
        assertThat(cfg.backoffFor(1)).isEqualTo(Duration.ofMillis(1000));
        assertThat(cfg.backoffFor(2)).isEqualTo(Duration.ofMillis(2000));
        assertThat(cfg.backoffFor(3)).isEqualTo(Duration.ofMillis(4000));
        assertThat(cfg.backoffFor(4)).isEqualTo(Duration.ofMillis(8000));
        assertThat(cfg.backoffFor(5)).isEqualTo(Duration.ofMillis(8000)); // capped
        assertThat(cfg.backoffFor(20)).isEqualTo(Duration.ofMillis(8000)); // 仍被 cap
    }

    @Test
    void backoffForZeroOrNegativeAttemptTreatedAsOne() {
        WebSocketConfig cfg = WebSocketConfig.builder()
                .initialBackoff(Duration.ofSeconds(2))
                .maxBackoff(Duration.ofSeconds(60))
                .build();
        assertThat(cfg.backoffFor(0)).isEqualTo(Duration.ofMillis(2000));
        assertThat(cfg.backoffFor(-1)).isEqualTo(Duration.ofMillis(2000));
    }

    @Test
    void rejectsBackoffMultiplierLeqOne() {
        assertThatThrownBy(() -> WebSocketConfig.builder().backoffMultiplier(1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backoffMultiplier");
        assertThatThrownBy(() -> WebSocketConfig.builder().backoffMultiplier(0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsZeroOrNegativeInitialBackoff() {
        assertThatThrownBy(() -> WebSocketConfig.builder().initialBackoff(Duration.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initialBackoff");
        assertThatThrownBy(() -> WebSocketConfig.builder()
                .initialBackoff(Duration.ofSeconds(-1)).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMaxBackoffSmallerThanInitial() {
        assertThatThrownBy(() -> WebSocketConfig.builder()
                .initialBackoff(Duration.ofSeconds(10))
                .maxBackoff(Duration.ofSeconds(5))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxBackoff");
    }

    @Test
    void maxAttemptsAcceptsNullAsInfinite() {
        WebSocketConfig cfg = WebSocketConfig.builder()
                .maxReconnectAttempts(null)
                .build();
        assertThat(cfg.maxReconnectAttempts()).isEmpty();
    }

    @Test
    void maxAttemptsAcceptsNegativeAsInfinite() {
        WebSocketConfig cfg = WebSocketConfig.builder()
                .maxReconnectAttempts(-1)
                .build();
        assertThat(cfg.maxReconnectAttempts()).isEmpty();
    }

    @Test
    void maxAttemptsAcceptsBoundedValue() {
        WebSocketConfig cfg = WebSocketConfig.builder()
                .maxReconnectAttempts(5)
                .build();
        assertThat(cfg.maxReconnectAttempts()).hasValue(5);
    }

    @Test
    void connectionStateBooleansAreCorrect() {
        assertThat(ConnectionState.CONNECTED.isActive()).isTrue();
        assertThat(ConnectionState.CONNECTING.isActive()).isFalse();
        assertThat(ConnectionState.CLOSED.isTerminated()).isTrue();
        assertThat(ConnectionState.DISCONNECTED.isTerminated()).isFalse();
    }
}
