package com.polymarket.clob.ws;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Polymarket WebSocket 客户端的配置项，覆盖：连接超时、指数退避重连、回调线程池
 * 与底层 {@link HttpClient}。对应 Rust {@code Config + ReconnectConfig}。
 *
 * <p>采用 builder 模式构造，所有字段都有合理默认值，调用方只需要按需要 override。
 * 默认与 Rust SDK 对齐：</p>
 * <ul>
 *   <li>{@code initialBackoff} = 1s，{@code maxBackoff} = 60s，{@code multiplier} = 2.0</li>
 *   <li>{@code maxAttempts} = 不限（infinite）</li>
 *   <li>{@code connectTimeout} = 10s</li>
 *   <li>{@code listenerExecutor} = {@link ForkJoinPool#commonPool()}</li>
 * </ul>
 *
 * <p>说明：spec §10 中提到的"心跳保活"首版不实现（与 Rust 心跳行为一致：
 * 服务端不响应标准 ws-ping，自定义 text "PING/PONG" 触发一次断点重连后即可恢复，
 * 收益不显著）。这里保留 {@link #heartbeatInterval} 字段以便后续扩展。</p>
 */
public final class WebSocketConfig {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(60);
    private static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;

    /**
     * 默认 {@link HttpClient}：进程内 lazy 单例，避免每次 new 时累积 selector 线程
     * （JDK 17 的 HttpClient 没有 close()，重复创建会泄漏 worker）。
     */
    private static final class DefaultHttpClientHolder {
        static final HttpClient INSTANCE = HttpClient.newBuilder()
                .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                .build();
    }

    private final Duration connectTimeout;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final double backoffMultiplier;
    private final OptionalInt maxReconnectAttempts;
    private final Duration heartbeatInterval;
    private final HttpClient httpClient;
    private final Executor listenerExecutor;

    private WebSocketConfig(Builder b) {
        this.connectTimeout = Objects.requireNonNullElse(b.connectTimeout, DEFAULT_CONNECT_TIMEOUT);
        this.initialBackoff = Objects.requireNonNullElse(b.initialBackoff, DEFAULT_INITIAL_BACKOFF);
        this.maxBackoff = Objects.requireNonNullElse(b.maxBackoff, DEFAULT_MAX_BACKOFF);
        this.backoffMultiplier = b.backoffMultiplier > 1.0 ? b.backoffMultiplier : DEFAULT_BACKOFF_MULTIPLIER;
        this.maxReconnectAttempts = b.maxReconnectAttempts;
        this.heartbeatInterval = b.heartbeatInterval; // null = 关闭心跳
        this.httpClient = Objects.requireNonNullElse(b.httpClient, DefaultHttpClientHolder.INSTANCE);
        this.listenerExecutor = Objects.requireNonNullElse(b.listenerExecutor, ForkJoinPool.commonPool());

        if (this.initialBackoff.isNegative() || this.initialBackoff.isZero()) {
            throw new IllegalArgumentException("initialBackoff must be positive");
        }
        if (this.maxBackoff.compareTo(this.initialBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must be >= initialBackoff");
        }
    }

    public Duration connectTimeout() { return connectTimeout; }
    public Duration initialBackoff() { return initialBackoff; }
    public Duration maxBackoff() { return maxBackoff; }
    public double backoffMultiplier() { return backoffMultiplier; }
    public OptionalInt maxReconnectAttempts() { return maxReconnectAttempts; }

    /** 心跳间隔（{@code null} 表示不发心跳；当前默认即 null）。 */
    public Duration heartbeatInterval() { return heartbeatInterval; }
    public HttpClient httpClient() { return httpClient; }
    public Executor listenerExecutor() { return listenerExecutor; }

    /**
     * 计算第 {@code attempt} 次重连前的退避时长（{@code attempt} 从 1 开始）。
     * 不带 jitter——测试期望的可预测性更重要；上线后偶发集中重连风险靠服务端保护。
     */
    public Duration backoffFor(int attempt) {
        if (attempt < 1) attempt = 1;
        double millis = initialBackoff.toMillis() * Math.pow(backoffMultiplier, attempt - 1);
        long capped = Math.min(maxBackoff.toMillis(), (long) Math.min(millis, Long.MAX_VALUE));
        return Duration.ofMillis(Math.max(capped, 0L));
    }

    public static WebSocketConfig defaults() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Duration connectTimeout;
        private Duration initialBackoff;
        private Duration maxBackoff;
        private double backoffMultiplier = DEFAULT_BACKOFF_MULTIPLIER;
        private OptionalInt maxReconnectAttempts = OptionalInt.empty();
        private Duration heartbeatInterval; // null means disabled
        private HttpClient httpClient;
        private Executor listenerExecutor;

        public Builder connectTimeout(Duration v) { this.connectTimeout = v; return this; }
        public Builder initialBackoff(Duration v) { this.initialBackoff = v; return this; }
        public Builder maxBackoff(Duration v) { this.maxBackoff = v; return this; }

        public Builder backoffMultiplier(double v) {
            if (v <= 1.0) {
                throw new IllegalArgumentException("backoffMultiplier must be > 1.0, got " + v);
            }
            this.backoffMultiplier = v;
            return this;
        }

        /** {@code null} 或负值表示无限重连。 */
        public Builder maxReconnectAttempts(Integer attempts) {
            this.maxReconnectAttempts = (attempts == null || attempts < 0)
                    ? OptionalInt.empty() : OptionalInt.of(attempts);
            return this;
        }

        public Builder heartbeatInterval(Duration v) { this.heartbeatInterval = v; return this; }
        public Builder httpClient(HttpClient v) { this.httpClient = v; return this; }
        public Builder listenerExecutor(Executor v) { this.listenerExecutor = v; return this; }

        public WebSocketConfig build() { return new WebSocketConfig(this); }
    }
}
