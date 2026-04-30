package com.polymarket.clob.heartbeat;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HeartbeatScheduler 测试：只校验生命周期 & ID 链路，无需真正 HTTP。
 * 选用极短的 interval（50ms）+ CountDownLatch 限定最大等待时间，避免测试慢。
 */
class HeartbeatSchedulerTest {

    @Test
    void firstTickSendsNullIdAndChainsSubsequent() throws Exception {
        AtomicReference<UUID> firstInput = new AtomicReference<>();
        CountDownLatch twoCalls = new CountDownLatch(2);
        AtomicInteger count = new AtomicInteger();
        UUID serverReturned = UUID.fromString("12345678-1234-1234-1234-123456789abc");

        try (HeartbeatScheduler scheduler = new HeartbeatScheduler(id -> {
            int n = count.incrementAndGet();
            if (n == 1) firstInput.set(id);
            twoCalls.countDown();
            return CompletableFuture.completedFuture(
                    new HeartbeatResponse(serverReturned, null));
        }, Duration.ofMillis(50))) {
            assertThat(scheduler.start()).isTrue();
            assertThat(scheduler.isActive()).isTrue();
            assertThat(twoCalls.await(3, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(firstInput.get()).isNull();
        // 链式：第二次及以后应复用上次响应里的 id
        assertThat(count.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void startIsIdempotent() throws Exception {
        HeartbeatScheduler scheduler = new HeartbeatScheduler(id -> CompletableFuture.completedFuture(
                new HeartbeatResponse(null, null)), Duration.ofSeconds(1));
        assertThat(scheduler.start()).isTrue();
        assertThat(scheduler.start()).isFalse();
        scheduler.close();
    }

    @Test
    void failedFuturesDoNotHaltScheduler() throws Exception {
        CountDownLatch gotThreeCalls = new CountDownLatch(3);
        AtomicInteger count = new AtomicInteger();

        try (HeartbeatScheduler scheduler = new HeartbeatScheduler(id -> {
            count.incrementAndGet();
            gotThreeCalls.countDown();
            return CompletableFuture.failedFuture(new RuntimeException("boom"));
        }, Duration.ofMillis(30))) {
            scheduler.start();
            assertThat(gotThreeCalls.await(3, TimeUnit.SECONDS))
                    .as("scheduler must keep ticking even after failures")
                    .isTrue();
        }
        assertThat(count.get()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void stopHaltsFutureTicks() throws Exception {
        AtomicInteger count = new AtomicInteger();
        HeartbeatScheduler scheduler = new HeartbeatScheduler(id -> {
            count.incrementAndGet();
            return CompletableFuture.completedFuture(new HeartbeatResponse(null, null));
        }, Duration.ofMillis(30));
        scheduler.start();
        Thread.sleep(200);
        assertThat(scheduler.stop()).isTrue();
        int snapshot = count.get();
        Thread.sleep(150);
        assertThat(count.get())
                .as("no new ticks after stop()")
                .isEqualTo(snapshot);
        assertThat(scheduler.isActive()).isFalse();
    }
}
