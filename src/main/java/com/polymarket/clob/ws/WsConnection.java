package com.polymarket.clob.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 单个 Polymarket WebSocket channel 的底层连接管理器：包装 JDK 自带
 * {@link java.net.http.WebSocket}，叠加：
 * <ol>
 *   <li>分片（fragment）text frame 自动拼装；</li>
 *   <li>断线后指数退避自动重连；</li>
 *   <li>state 变化与"重连成功"回调，供上层 {@code SubscriptionRegistry} 重放订阅；</li>
 *   <li>顺序化 send queue，避免 JDK WebSocket 同时只能 in-flight 1 个 send 的限制。</li>
 * </ol>
 *
 * <p>对应 Rust {@code ConnectionManager}（{@code rs-clob-client/src/ws/connection.rs}）。
 * Java 这边没用 broadcast channel，直接用单一 {@link MessageHandler} 回调——按
 * spec §10 的 listener 模型，单 socket 的解析后路由由上层 {@code SubscriptionRegistry}
 * 完成，{@link WsConnection} 自身无需关心订阅。</p>
 *
 * <p>线程模型：</p>
 * <ul>
 *   <li>JDK WebSocket 内部使用 selector 线程触发 {@link java.net.http.WebSocket.Listener}
 *       回调，本类的 {@code onText} 直接在那里拼装 frame，拼装完成后切到
 *       {@link WebSocketConfig#listenerExecutor()} 调用业务 handler；</li>
 *   <li>退避重连用一个独立的 single-thread daemon scheduler；</li>
 *   <li>send queue 用 {@link ConcurrentLinkedQueue} 串行化 sendText 调用。</li>
 * </ul>
 *
 * <p>{@link AutoCloseable#close()} 是终态：调用后状态变 {@link ConnectionState#CLOSED}，
 * 不会再重连。</p>
 */
public final class WsConnection implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(WsConnection.class);

    private final URI endpoint;
    private final WebSocketConfig config;
    private final MessageHandler handler;

    private final ScheduledExecutorService scheduler;
    private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.DISCONNECTED);
    private final AtomicReference<WebSocket> currentSocket = new AtomicReference<>();
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    private final ConcurrentLinkedQueue<Consumer<ConnectionState>> stateListeners = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Runnable> connectedListeners = new ConcurrentLinkedQueue<>();

    /**
     * Send queue 串行化锚点：每次 sendText 都 chain 在它后面，保证 JDK WebSocket
     * 不会触发 {@code IllegalStateException: send pending}。
     */
    private CompletableFuture<WebSocket> sendChain = CompletableFuture.completedFuture(null);
    private final Object sendLock = new Object();

    public WsConnection(URI endpoint, WebSocketConfig config, MessageHandler handler) {
        this.endpoint = java.util.Objects.requireNonNull(endpoint, "endpoint");
        this.config = java.util.Objects.requireNonNull(config, "config");
        this.handler = java.util.Objects.requireNonNull(handler, "handler");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "clob-ws-reconnect-" + endpoint.getHost());
            t.setDaemon(true);
            return t;
        });
    }

    /** 注册状态变化回调（多次注册按 FIFO 顺序触发）。 */
    public void addStateListener(Consumer<ConnectionState> l) {
        stateListeners.add(java.util.Objects.requireNonNull(l, "listener"));
    }

    /** 注册"每次成功（首连或重连）后"的回调，用于重放订阅。 */
    public void addConnectedListener(Runnable l) {
        connectedListeners.add(java.util.Objects.requireNonNull(l, "listener"));
    }

    public ConnectionState state() {
        return state.get();
    }

    public int reconnectAttempts() {
        return reconnectAttempts.get();
    }

    public URI endpoint() {
        return endpoint;
    }

    /**
     * 启动连接（异步）。返回的 future 在第一次握手成功时完成；首次失败也会进入
     * 重连流程，但首次 future 仍以异常完成，方便调用方感知"连不上"。
     */
    public CompletableFuture<Void> connect() {
        if (state.get() == ConnectionState.CLOSED) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "connect() called after close()"));
        }
        if (!transitionTo(ConnectionState.CONNECTING)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "connect() called in state " + state.get()));
        }
        return doBuildSocket().thenAccept(ws -> {
            // 与 close() 的 race：connect future 完成时如果已 CLOSED，直接 abort 新 socket 防泄漏。
            if (state.get() == ConnectionState.CLOSED) {
                ws.abort();
                return;
            }
            currentSocket.set(ws);
            reconnectAttempts.set(0);
            transitionTo(ConnectionState.CONNECTED);
            fireConnected();
        }).whenComplete((unused, ex) -> {
            if (ex != null && state.get() != ConnectionState.CLOSED) {
                LOG.warn("Initial WS connection to {} failed: {}", endpoint, ex.toString());
                handler.onError(ex);
                scheduleReconnect();
            }
        });
    }

    /**
     * 异步发送一条 text 消息。多次调用按 FIFO 串行化（JDK WebSocket 不允许并发 send）。
     * 在断线 / 关闭状态下立即返回失败的 future。
     */
    public CompletableFuture<Void> sendText(String text) {
        java.util.Objects.requireNonNull(text, "text");
        ConnectionState s = state.get();
        if (s == ConnectionState.CLOSED || s == ConnectionState.DISCONNECTED) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Cannot send: connection state is " + s));
        }
        WebSocket ws = currentSocket.get();
        if (ws == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "WebSocket not yet established (state=" + s + ")"));
        }
        synchronized (sendLock) {
            CompletableFuture<WebSocket> next = sendChain.thenCompose(
                    ignored -> ws.sendText(text, true)).toCompletableFuture();
            sendChain = next.exceptionally(ex -> ws);
            return next.thenApply(w -> (Void) null);
        }
    }

    @Override
    public void close() {
        if (state.getAndSet(ConnectionState.CLOSED) == ConnectionState.CLOSED) {
            return;
        }
        notifyStateListeners(ConnectionState.CLOSED);
        scheduler.shutdownNow();
        WebSocket ws = currentSocket.getAndSet(null);
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "client closing").get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                ws.abort();
            }
        }
    }

    private CompletableFuture<WebSocket> doBuildSocket() {
        InternalListener listener = new InternalListener();
        return config.httpClient().newWebSocketBuilder()
                .connectTimeout(config.connectTimeout())
                .buildAsync(endpoint, listener);
    }

    private void scheduleReconnect() {
        if (state.get() == ConnectionState.CLOSED) {
            return;
        }
        OptionalInt maxAttempts = config.maxReconnectAttempts();
        int next = reconnectAttempts.incrementAndGet();
        if (maxAttempts.isPresent() && next > maxAttempts.getAsInt()) {
            LOG.error("Exceeded max reconnect attempts ({}) for {}", maxAttempts.getAsInt(), endpoint);
            transitionTo(ConnectionState.DISCONNECTED);
            return;
        }

        Duration delay = config.backoffFor(next);
        transitionTo(ConnectionState.RECONNECTING);
        LOG.info("Scheduling WS reconnect attempt {} to {} after {}ms", next, endpoint, delay.toMillis());

        scheduler.schedule(this::attemptReconnect, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void attemptReconnect() {
        if (state.get() == ConnectionState.CLOSED) {
            return;
        }
        transitionTo(ConnectionState.CONNECTING);
        doBuildSocket().whenComplete((ws, ex) -> {
            if (state.get() == ConnectionState.CLOSED) {
                if (ws != null) ws.abort();
                return;
            }
            if (ex != null) {
                LOG.warn("Reconnect attempt {} to {} failed: {}",
                        reconnectAttempts.get(), endpoint, ex.toString());
                handler.onError(ex);
                scheduleReconnect();
                return;
            }
            currentSocket.set(ws);
            reconnectAttempts.set(0);
            transitionTo(ConnectionState.CONNECTED);
            fireConnected();
        });
    }

    /**
     * 用 CAS 切到目标状态：{@link ConnectionState#CLOSED} 是终态，一旦进入永远拒绝复活。
     * 同状态 → 同状态视为 no-op，不重复触发 listener。
     */
    private boolean transitionTo(ConnectionState target) {
        while (true) {
            ConnectionState prev = state.get();
            if (prev == ConnectionState.CLOSED && target != ConnectionState.CLOSED) {
                return false;
            }
            if (prev == target) {
                return true;
            }
            if (state.compareAndSet(prev, target)) {
                notifyStateListeners(target);
                return true;
            }
        }
    }

    private void notifyStateListeners(ConnectionState s) {
        for (Consumer<ConnectionState> l : stateListeners) {
            try {
                config.listenerExecutor().execute(() -> l.accept(s));
            } catch (RuntimeException ex) {
                LOG.warn("State listener threw {}", ex.toString());
            }
        }
    }

    private void fireConnected() {
        for (Runnable r : connectedListeners) {
            try {
                config.listenerExecutor().execute(r);
            } catch (RuntimeException ex) {
                LOG.warn("Connected listener threw {}", ex.toString());
            }
        }
    }

    /**
     * 内部 {@link java.net.http.WebSocket.Listener}：累积 fragment、捕获 close/error，
     * 触发上层重连。
     */
    private final class InternalListener implements WebSocket.Listener {

        private final StringBuilder textBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
            // state 切到 CONNECTED 由 connect()/attemptReconnect() 的 future 完成处统一处理，
            // 避免双源驱动状态机。
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String message = textBuffer.toString();
                textBuffer.setLength(0);
                config.listenerExecutor().execute(() -> {
                    try {
                        handler.onText(message);
                    } catch (RuntimeException e) {
                        LOG.warn("MessageHandler#onText threw {}", e.toString());
                        handler.onError(e);
                    }
                });
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            LOG.info("WS closed by peer: code={}, reason={}, endpoint={}", statusCode, reason, endpoint);
            currentSocket.compareAndSet(webSocket, null);
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            LOG.warn("WS onError: {} (endpoint={})", error.toString(), endpoint);
            currentSocket.compareAndSet(webSocket, null);
            handler.onError(error);
            scheduleReconnect();
        }
    }
}
