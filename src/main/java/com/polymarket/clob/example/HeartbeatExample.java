package com.polymarket.clob.example;

import com.polymarket.clob.AuthenticatedClobClient;
import com.polymarket.clob.ClobClient;
import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.heartbeat.HeartbeatResponse;
import com.polymarket.clob.heartbeat.HeartbeatScheduler;
import com.polymarket.clob.model.ChainId;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Optional;

/**
 * Heartbeat 示例：演示两种使用方式。
 *
 * <ol>
 *   <li><b>单次心跳</b>（默认）：手动调用 {@link AuthenticatedClobClient#postHeartbeat}，
 *       服务端返回的 {@code heartbeatId} 需由调用方在后续请求里回传；</li>
 *   <li><b>后台调度</b>（{@code MODE=scheduler}）：启动 {@link HeartbeatScheduler}，
 *       自动维护 id 链路、失败不中断。示例运行 {@code DURATION_SECONDS}（默认 15）秒后关闭。</li>
 * </ol>
 *
 * <p>环境变量：同 {@link AuthenticatedExample}；另外：
 * <ul>
 *   <li>{@code MODE}：{@code once}（默认）或 {@code scheduler}</li>
 *   <li>{@code HEARTBEAT_INTERVAL_SECONDS}：scheduler 间隔，默认 5（对齐 Rust）</li>
 *   <li>{@code DURATION_SECONDS}：scheduler 运行时长，默认 15</li>
 * </ul>
 *
 * <p>运行：
 * <pre>{@code
 * export CLOB_PRIVATE_KEY=0xac0974...
 * # 单次心跳
 * mvn -B compile exec:java \
 *     -Dexec.mainClass=com.polymarket.clob.example.HeartbeatExample
 * # 后台调度
 * MODE=scheduler DURATION_SECONDS=30 mvn -B compile exec:java \
 *     -Dexec.mainClass=com.polymarket.clob.example.HeartbeatExample
 * }</pre>
 */
public final class HeartbeatExample {

    private HeartbeatExample() {}

    public static void main(String[] args) throws Exception {
        String pk = require("CLOB_PRIVATE_KEY");
        String endpoint = Optional.ofNullable(System.getenv("CLOB_ENDPOINT"))
                .orElse("https://clob.polymarket.com");
        long chainId = parseChainId(System.getenv("CLOB_CHAIN_ID"));
        SignatureType sigType = parseSignatureType(System.getenv("CLOB_SIGNATURE_TYPE"));
        String mode = Optional.ofNullable(System.getenv("MODE")).orElse("once").trim().toLowerCase();

        Signer signer = LocalSigner.fromPrivateKey(pk);

        try (ClobClient base = ClobClient.builder()
                .endpoint(endpoint)
                .chainId(chainId)
                .build()) {

            AuthenticatedClobClient client = base
                    .authenticate(signer, sigType, BigInteger.ZERO)
                    .join();
            System.out.println("authed funder = " + client.funder().toHex());

            switch (mode) {
                case "scheduler" -> runScheduler(client);
                case "once" -> runOnce(client);
                default -> throw new IllegalStateException("unknown MODE=" + mode);
            }
        }
    }

    private static void runOnce(AuthenticatedClobClient client) {
        HeartbeatResponse first = client.postHeartbeat(null).join();
        System.out.println("first heartbeat -> id=" + first.heartbeatId()
                + (first.error() == null ? "" : " warning=" + first.error()));

        HeartbeatResponse second = client.postHeartbeat(first.heartbeatId()).join();
        System.out.println("chain heartbeat -> id=" + second.heartbeatId()
                + (second.error() == null ? "" : " warning=" + second.error()));
    }

    private static void runScheduler(AuthenticatedClobClient client) throws Exception {
        Duration interval = Duration.ofSeconds(Long.parseLong(
                Optional.ofNullable(System.getenv("HEARTBEAT_INTERVAL_SECONDS")).orElse("5")));
        long durationSeconds = Long.parseLong(
                Optional.ofNullable(System.getenv("DURATION_SECONDS")).orElse("15"));

        System.out.println("starting heartbeats interval=" + interval + " duration=" + durationSeconds + "s");
        try (HeartbeatScheduler scheduler = client.startHeartbeats(interval)) {
            for (long left = durationSeconds; left > 0; left--) {
                Thread.sleep(1000);
                System.out.println(".. last_id=" + scheduler.lastHeartbeatId()
                        + " active=" + scheduler.isActive() + " (" + left + "s left)");
            }
            scheduler.stop();
            System.out.println("scheduler stopped. active=" + scheduler.isActive());
        }
    }

    private static String require(String env) {
        String v = System.getenv(env);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("环境变量 " + env + " 未设置");
        }
        return v;
    }

    private static long parseChainId(String raw) {
        if (raw == null || raw.isBlank() || "POLYGON".equalsIgnoreCase(raw)) {
            return ChainId.POLYGON;
        }
        if ("AMOY".equalsIgnoreCase(raw)) return ChainId.AMOY;
        return Long.parseLong(raw);
    }

    private static SignatureType parseSignatureType(String raw) {
        if (raw == null || raw.isBlank()) return SignatureType.EOA;
        return SignatureType.valueOf(raw.trim().toUpperCase());
    }
}
