package com.polymarket.clob.example;

import com.polymarket.clob.AuthenticatedClobClient;
import com.polymarket.clob.BuilderClobClient;
import com.polymarket.clob.ClobClient;
import com.polymarket.clob.api.model.BuilderApiKeyResponse;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.auth.BuilderConfig;
import com.polymarket.clob.model.ChainId;
import com.polymarket.clob.trade.BuilderTrade;
import com.polymarket.clob.trade.Trade;
import com.polymarket.clob.trade.TradesRequest;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/**
 * Builder 示例：演示 {@code promoteToBuilder} 全流程。
 *
 * <ol>
 *   <li>普通 L1/L2 认证（同 {@link AuthenticatedExample}）；</li>
 *   <li>使用 {@code BUILDER_*} 环境变量构造 {@link BuilderConfig}（本地或远程 signing）；</li>
 *   <li>升级到 {@link BuilderClobClient}：</li>
 *   <ul>
 *       <li>{@link BuilderClobClient#getTrades} — 普通 {@code /data/trades}；</li>
 *       <li>{@link BuilderClobClient#getBuilderTrades} — Builder 专属 {@code /builder/trades}；</li>
 *       <li>{@link BuilderClobClient#listBuilderApiKeys} — 列出当前 Builder 持有的 API Keys。</li>
 *   </ul>
 * </ol>
 *
 * <p>环境变量：
 * <ul>
 *   <li>{@code CLOB_PRIVATE_KEY}（必填）</li>
 *   <li>{@code CLOB_ENDPOINT} / {@code CLOB_CHAIN_ID} / {@code CLOB_SIGNATURE_TYPE}（同其它示例）</li>
 *   <li>Builder 模式二选一：
 *     <ul>
 *       <li><b>本地</b>：{@code BUILDER_API_KEY} + {@code BUILDER_API_SECRET} + {@code BUILDER_PASSPHRASE}</li>
 *       <li><b>远程</b>：{@code BUILDER_REMOTE_HOST}（可选 {@code BUILDER_REMOTE_TOKEN}）</li>
 *     </ul>
 *   </li>
 *   <li>{@code TRADES_LIMIT}（可选，默认 5）：每类 trade 流最多打印前 N 条。</li>
 * </ul>
 *
 * <p>运行：
 * <pre>{@code
 * export CLOB_PRIVATE_KEY=0xac0974...
 * # 本地 Builder 凭证
 * export BUILDER_API_KEY=... BUILDER_API_SECRET=... BUILDER_PASSPHRASE=...
 * mvn -B compile exec:java \
 *     -Dexec.mainClass=com.polymarket.clob.example.BuilderExample
 * }</pre>
 */
public final class BuilderExample {

    private BuilderExample() {}

    public static void main(String[] args) {
        String pk = require("CLOB_PRIVATE_KEY");
        String endpoint = Optional.ofNullable(System.getenv("CLOB_ENDPOINT"))
                .orElse("https://clob.polymarket.com");
        long chainId = parseChainId(System.getenv("CLOB_CHAIN_ID"));
        SignatureType sigType = parseSignatureType(System.getenv("CLOB_SIGNATURE_TYPE"));
        int limit = Integer.parseInt(Optional.ofNullable(System.getenv("TRADES_LIMIT")).orElse("5"));

        BuilderConfig builderCfg = loadBuilderConfig();
        Signer signer = LocalSigner.fromPrivateKey(pk);

        try (ClobClient base = ClobClient.builder()
                .endpoint(endpoint)
                .chainId(chainId)
                .build()) {

            AuthenticatedClobClient authed = base
                    .authenticate(signer, sigType, BigInteger.ZERO)
                    .join();
            System.out.println("authed funder = " + authed.funder().toHex());

            // typestate 升级：得到 BuilderClobClient
            BuilderClobClient builder = authed.promoteToBuilder(builderCfg);
            System.out.println("builder config = " + describe(builderCfg));

            // ---- 普通 trades（L2 认证）----
            System.out.println("\n--- /data/trades（前 " + limit + " 条）---");
            List<Trade> mine = builder.getTrades(TradesRequest.none())
                    .limit(limit)
                    .toList();
            if (mine.isEmpty()) {
                System.out.println("(无 trade 记录)");
            } else {
                mine.forEach(t -> System.out.printf(
                        "  id=%s side=%s price=%s size=%s status=%s%n",
                        t.getId(), t.getSide(), t.getPrice(), t.getSize(), t.getStatus()));
            }

            // ---- Builder trades（L2 + POLY_BUILDER_*）----
            System.out.println("\n--- /builder/trades（前 " + limit + " 条）---");
            List<BuilderTrade> btrades = builder.getBuilderTrades(TradesRequest.none())
                    .limit(limit)
                    .toList();
            if (btrades.isEmpty()) {
                System.out.println("(无 builder trade 记录 — 若是首次使用请确认 Builder 凭证已激活)");
            } else {
                btrades.forEach(t -> System.out.printf(
                        "  id=%s tradeType=%s side=%s price=%s status=%s%n",
                        t.getId(), t.getTradeType(), t.getSide(), t.getPrice(), t.getStatus()));
            }

            // ---- Builder API Keys 元信息 ----
            System.out.println("\n--- /auth/builder-api-key（当前凭证关联的 key 列表）---");
            List<BuilderApiKeyResponse> keys = builder.listBuilderApiKeys().join();
            keys.forEach(k -> System.out.printf(
                    "  key=%s createdAt=%s revokedAt=%s active=%s%n",
                    k.getKey(), k.getCreatedAt(), k.getRevokedAt(), k.isActive()));

            // ---- 可选：发一次心跳（不阻塞关闭）----
            if ("1".equals(System.getenv("HEARTBEAT"))) {
                var resp = builder.postHeartbeat(null).join();
                System.out.println("\nheartbeat -> id=" + resp.heartbeatId()
                        + (resp.error() != null ? " warning=" + resp.error() : ""));
            }
        }
    }

    // ---------------- helpers ----------------

    /**
     * 根据环境变量构造 {@link BuilderConfig}。优先级：
     * <ol>
     *   <li>{@code BUILDER_REMOTE_HOST} 非空 → {@link BuilderConfig#remote}；</li>
     *   <li>{@code BUILDER_API_KEY/SECRET/PASSPHRASE} 全部非空 → {@link BuilderConfig#local}；</li>
     *   <li>都没有 → 抛错，明确提示可用变量。</li>
     * </ol>
     */
    private static BuilderConfig loadBuilderConfig() {
        String remoteHost = System.getenv("BUILDER_REMOTE_HOST");
        if (remoteHost != null && !remoteHost.isBlank()) {
            return BuilderConfig.remote(remoteHost, System.getenv("BUILDER_REMOTE_TOKEN"));
        }
        String apiKey = System.getenv("BUILDER_API_KEY");
        String secret = System.getenv("BUILDER_API_SECRET");
        String pass = System.getenv("BUILDER_PASSPHRASE");
        if (apiKey != null && secret != null && pass != null
                && !apiKey.isBlank() && !secret.isBlank() && !pass.isBlank()) {
            return BuilderConfig.local(new ApiCredentials(apiKey, secret, pass));
        }
        throw new IllegalStateException(
                "请配置 BUILDER_REMOTE_HOST 或 BUILDER_API_KEY+BUILDER_API_SECRET+BUILDER_PASSPHRASE。");
    }

    private static String describe(BuilderConfig cfg) {
        if (cfg instanceof BuilderConfig.Local) return "LOCAL";
        if (cfg instanceof BuilderConfig.Remote r) return "REMOTE(" + r.host() + ")";
        return cfg.getClass().getSimpleName();
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
