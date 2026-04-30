package com.polymarket.clob.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.polymarket.clob.ClobClient;
import com.polymarket.clob.http.RequestCaptor;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 把 {@code parity/inputs/*.json} 派发到 SDK 调用，并通过 {@link RequestCaptor}
 * 抓取出站第一条 HTTP 请求，转成 {@link ParityRequestSnapshot}。
 *
 * <p>当前 task 只支持 {@code get_ok} kind（spec §7.2 列表第一项），目标是验证
 * 整条管道接通；其他 kind 在 Task 13 / Task 14 增补。其他 kind 会以
 * {@link UnsupportedOperationException} 失败完成 future，便于上层 dispatcher
 * 测试驱动下个 task 的 TDD 红灯。</p>
 *
 * <p>所有 SDK 调用通过 {@link ClobClient#builder()} 注入：
 * <ul>
 *   <li>{@code clock}：从 input.frozen.timestamp 派生 {@link Clock#fixed}</li>
 *   <li>{@code requestCaptor}：写入 {@link AtomicReference}，take-once 语义</li>
 * </ul>
 * </p>
 */
public final class ParityFixtureDispatcher {

    private ParityFixtureDispatcher() {}

    /**
     * 按 input 派发并返回捕获到的请求快照。
     *
     * <p>语义：成功路径上 captor 抓到第一条请求 → 返回 snapshot；如果
     * SDK 在 captor 触发前抛错，则 future 以包装的 {@link RuntimeException} 完成。</p>
     */
    public static CompletableFuture<ParityRequestSnapshot> dispatchAndCapture(JsonNode input) {
        String kind = input.path("call").path("kind").asText();
        long ts = input.path("frozen").path("timestamp").asLong(1700000000L);
        String host = input.path("config").path("host").asText();
        long chainId = chainIdOf(input.path("config").path("chain_id").asText("POLYGON"));

        Clock fixed = Clock.fixed(Instant.ofEpochSecond(ts), ZoneOffset.UTC);
        AtomicReference<ParityRequestSnapshot> captured = new AtomicReference<>();
        RequestCaptor cap = (m, u, h, b) -> captured.compareAndSet(
                null,
                ParityRequestSnapshot.http(
                        m,
                        u.toString(),
                        HeaderNormalizer.normalize(h),
                        b == null ? new byte[0] : b));

        ClobClient client = ClobClient.builder()
                .endpoint(URI.create(host))
                .chainId(chainId)
                .clock(fixed)
                .requestCaptor(cap)
                .build();

        return invoke(client, kind, input.path("call").path("args"))
                .handle((r, t) -> snapshotOrThrow(captured, t));
    }

    private static CompletableFuture<?> invoke(ClobClient client, String kind, JsonNode args) {
        return switch (kind) {
            case "get_ok" -> client.market().ok();
            default -> CompletableFuture.failedFuture(
                    new UnsupportedOperationException(
                            "kind not supported in this task: " + kind));
        };
    }

    private static long chainIdOf(String name) {
        return switch (name) {
            case "POLYGON" -> 137L;
            case "AMOY" -> 80002L;
            default -> throw new IllegalArgumentException("unknown chain_id: " + name);
        };
    }

    private static ParityRequestSnapshot snapshotOrThrow(
            AtomicReference<ParityRequestSnapshot> ref, Throwable t) {
        ParityRequestSnapshot s = ref.get();
        if (s != null) {
            return s;
        }
        if (t != null) {
            // 若是 unsupported kind / 派发自身失败，向上抛；
            // 注意：SDK 网络阶段失败时 captor 已先触发，走上面的 s != null 分支。
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(t);
        }
        throw new IllegalStateException("dispatcher did not capture any outbound request");
    }

    /** 仅在调试时使用：把 args 节点转成 string map（一层），用于占位 dispatcher 调用桥接。 */
    @SuppressWarnings("unused")
    private static Map<String, String> flatStringArgs(JsonNode args) {
        if (args == null || args.isMissingNode() || !args.isObject()) {
            return Map.of();
        }
        var b = new java.util.LinkedHashMap<String, String>();
        args.fields().forEachRemaining(e -> b.put(e.getKey(), e.getValue().asText()));
        return Map.copyOf(b);
    }
}
