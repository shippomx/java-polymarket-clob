package com.polymarket.clob.heartbeat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 后台心跳调度器。对齐 Rust {@code start_heartbeats / stop_heartbeats}：
 *
 * <ul>
 *   <li>固定频率（默认 5 秒，对齐 Rust {@code heartbeat_interval}）调用
 *       {@link #poster} 传入的心跳函数。</li>
 *   <li>把上次响应的 {@code heartbeatId} 持续链式回传；首次为 {@code null}。</li>
 *   <li>任意一次调用失败仅记录日志，<b>不打断</b>调度：网络抖动是常态，
 *       上游期望客户端坚持不停歇；致命错误需要由调用方主动 {@link #stop()}。</li>
 * </ul>
 *
 * <p>线程模型：单线程 {@link ScheduledExecutorService}，daemon 线程，不会阻止 JVM 退出。
 * 本类本身线程安全，可在多线程环境中 {@link #start()}/{@link #stop()}；
 * 重复 {@code start} 不会启动第二个任务。</p>
 *
 * <p>与 Rust {@code heartbeats_active()} 等价的状态查询请用 {@link #isActive()}。</p>
 */
public final class HeartbeatScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatScheduler.class);

    /** 与 Rust {@code Config::default().heartbeat_interval} 对齐。 */
    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(5);

    private static final ThreadFactory DAEMON_FACTORY = r -> {
        Thread t = new Thread(r, "clob-heartbeat");
        t.setDaemon(true);
        return t;
    };

    private final Function<UUID, CompletableFuture<HeartbeatResponse>> poster;
    private final Duration interval;
    private final ScheduledExecutorService executor;
    private final boolean ownsExecutor;
    private final AtomicReference<UUID> lastId = new AtomicReference<>(null);
    private final AtomicBoolean active = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> task;

    /**
     * @param poster   心跳函数：输入上次 heartbeat_id（首次为 null），返回响应 future。
     *                 通常为 {@code id -> authedClient.postHeartbeat(id)} 的方法引用。
     * @param interval 心跳间隔；{@code null} 回退到 {@link #DEFAULT_INTERVAL}
     */
    public HeartbeatScheduler(
            Function<UUID, CompletableFuture<HeartbeatResponse>> poster,
            Duration interval) {
        this(poster, interval, null);
    }

    /**
     * 高级构造：允许调用方注入自定义 executor（便于复用线程池或做测试）。
     * 传入 {@code null} 时内部持有一个 daemon 单线程调度器并由本类负责关闭。
     */
    public HeartbeatScheduler(
            Function<UUID, CompletableFuture<HeartbeatResponse>> poster,
            Duration interval,
            ScheduledExecutorService executor) {
        this.poster = Objects.requireNonNull(poster, "poster");
        this.interval = interval == null ? DEFAULT_INTERVAL : interval;
        if (executor == null) {
            this.executor = Executors.newSingleThreadScheduledExecutor(DAEMON_FACTORY);
            this.ownsExecutor = true;
        } else {
            this.executor = executor;
            this.ownsExecutor = false;
        }
    }

    /**
     * 启动调度。已经运行则静默返回 {@code false}。首跳延迟 {@link #interval} 后发生
     * （对齐 Rust：首个 {@code ticker.tick()} 立即消费但不执行 body）。
     */
    public boolean start() {
        if (!active.compareAndSet(false, true)) {
            log.debug("heartbeat scheduler already active; ignoring start()");
            return false;
        }
        long periodMs = interval.toMillis();
        task = executor.scheduleAtFixedRate(this::tick, periodMs, periodMs, TimeUnit.MILLISECONDS);
        log.debug("heartbeat scheduler started (interval={}ms)", periodMs);
        return true;
    }

    /** 取消下一次 tick；正在飞行的心跳请求不会被打断（会走自然完成）。 */
    public boolean stop() {
        if (!active.compareAndSet(true, false)) {
            return false;
        }
        ScheduledFuture<?> t = task;
        if (t != null) t.cancel(false);
        task = null;
        log.debug("heartbeat scheduler stopped");
        return true;
    }

    /** 与 Rust {@code heartbeats_active()} 语义一致：是否处于启动态。 */
    public boolean isActive() {
        return active.get();
    }

    /** 最近一次成功心跳的 id；测试用。 */
    public UUID lastHeartbeatId() {
        return lastId.get();
    }

    /** 使用时间间隔。 */
    public Duration interval() {
        return interval;
    }

    private void tick() {
        if (!active.get()) return;
        UUID id = lastId.get();
        try {
            poster.apply(id).whenComplete((resp, err) -> {
                if (err != null) {
                    log.warn("heartbeat failed: {}", err.getMessage());
                    return;
                }
                if (resp != null && resp.heartbeatId() != null) {
                    lastId.set(resp.heartbeatId());
                    if (resp.error() != null && !resp.error().isBlank()) {
                        log.warn("heartbeat accepted with warning: {}", resp.error());
                    }
                }
            });
        } catch (RuntimeException e) {
            // poster 本身同步阶段就抛异常（例如未认证）也不能拆毁调度器
            log.warn("heartbeat scheduling error: {}", e.getMessage());
        }
    }

    /**
     * 关闭调度并释放资源。{@link #stop()} 幂等；如果内部持有 executor，这里一并关闭。
     * 外部注入的 executor 由调用方自行管理，本方法不会关闭它。
     */
    @Override
    public void close() {
        stop();
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }
}
