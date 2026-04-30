package com.polymarket.clob;

import com.polymarket.clob.api.AuthApi;
import com.polymarket.clob.api.AuthApiImpl;
import com.polymarket.clob.api.MarketDataApi;
import com.polymarket.clob.api.MarketDataApiImpl;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.auth.WalletDerivation;
import com.polymarket.clob.exception.ClobAuthException;
import com.polymarket.clob.http.HttpTransport;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.order.SaltSource;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.math.BigInteger;
import java.net.URI;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 未认证的 CLOB 客户端。仅暴露只读能力（{@link #market()}）。
 *
 * <p>后续 Plan 中将通过 {@code authenticate(signer, sigType)} 升级到
 * {@code AuthenticatedClobClient}，从而启用下单、查询余额等受保护端点。</p>
 *
 * <p>该类线程安全：所有字段 {@code final}，依赖的 {@link HttpTransport} / {@link MarketDataApi} 线程安全。</p>
 */
@Accessors(fluent = true)
public final class ClobClient implements AutoCloseable {

    @Getter
    private final URI endpoint;

    @Getter
    private final long chainId;

    private final HttpTransport transport;
    private final MarketDataApi market;
    private final Clock clock;
    private final SaltSource saltSource;

    /**
     * 旧 3 参构造器：默认 {@link Clock#systemUTC()} + {@link SaltSource#secureRandom()}。
     * 保留以避免影响现有 caller；新代码请走 5 参版本或 builder。
     */
    ClobClient(URI endpoint, long chainId, HttpTransport transport) {
        this(endpoint, chainId, transport, Clock.systemUTC(), SaltSource.secureRandom());
    }

    /**
     * 完整构造器：允许注入 {@link Clock} 与 {@link SaltSource}，
     * 用于 parity 测试中固定时间戳与 salt 生成可重现 golden 向量。
     * {@code null} 会回落到默认 systemUTC / secureRandom。
     */
    ClobClient(URI endpoint, long chainId, HttpTransport transport,
               Clock clock, SaltSource saltSource) {
        this.endpoint = endpoint;
        this.chainId = chainId;
        this.transport = transport;
        this.market = new MarketDataApiImpl(transport);
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.saltSource = saltSource == null ? SaltSource.secureRandom() : saltSource;
    }

    /** 创建一个新的 {@link ClobClientBuilder}。 */
    public static ClobClientBuilder builder() {
        return new ClobClientBuilder();
    }

    /** 只读市场数据 API。 */
    public MarketDataApi market() {
        return market;
    }

    /**
     * 当前注入的时钟。默认 {@link Clock#systemUTC()}；测试可用
     * {@link Clock#fixed} 锁定时间产出可重现签名。
     */
    public Clock clock() {
        return clock;
    }

    /**
     * 当前注入的 salt 生成策略。默认 {@link SaltSource#secureRandom()}；
     * 测试可用 {@link SaltSource#fixed} 固定 salt 产出 golden 向量。
     */
    public SaltSource saltSource() {
        return saltSource;
    }

    /** 内部可见：供包内测试和未来 typestate 升级使用。 */
    HttpTransport transport() {
        return transport;
    }

    /**
     * 升级为 {@link AuthenticatedClobClient}：直接使用已有的 {@link ApiCredentials}，跳过网络调用。
     *
     * <p>{@code funder} 按 {@link SignatureType} 从 {@code signer.address()} 派生：
     * EOA 直接用 EOA，PROXY 走 {@code CREATE2}，GNOSIS_SAFE 同理。派生失败（例如 Amoy 不支持
     * Proxy）抛出 {@link ClobAuthException}。</p>
     */
    public AuthenticatedClobClient authenticate(
            Signer signer, SignatureType signatureType, ApiCredentials credentials) {
        Objects.requireNonNull(signer, "signer");
        Objects.requireNonNull(signatureType, "signatureType");
        Objects.requireNonNull(credentials, "credentials");
        Address funder = WalletDerivation.deriveFunder(signatureType, signer.address(), chainId)
                .orElseThrow(() -> new ClobAuthException(
                        "Cannot derive funder for chainId=" + chainId + " type=" + signatureType));
        return new AuthenticatedClobClient(
                endpoint, chainId, transport, market,
                signer, signatureType, funder, credentials,
                clock, saltSource);
    }

    /**
     * 升级为 {@link AuthenticatedClobClient}：若未提供 {@code credentials} 则调用
     * {@code /auth/api-key}（优先 create，失败回落 derive）从链上取回。
     *
     * @param signer        L1 签名器
     * @param signatureType funder 派生方式
     * @param nonce         ClobAuth nonce；{@code null} 视作 0
     */
    public CompletableFuture<AuthenticatedClobClient> authenticate(
            Signer signer, SignatureType signatureType, BigInteger nonce) {
        Objects.requireNonNull(signer, "signer");
        Objects.requireNonNull(signatureType, "signatureType");
        AuthApi auth = new AuthApiImpl(transport, chainId);
        long timestamp = clock.instant().getEpochSecond();
        BigInteger n = Optional.ofNullable(nonce).orElse(BigInteger.ZERO);
        return auth.createOrDeriveApiKey(signer, chainId, timestamp, n)
                .thenApply(creds -> authenticate(signer, signatureType, creds));
    }

    /**
     * 释放客户端资源。
     *
     * <p><b>当前实现（JDK 17 基线）：no-op。</b>理由：
     * <ul>
     *   <li>JDK 17 的 {@link java.net.http.HttpClient} 不实现 {@link AutoCloseable}，
     *       其 selector 线程由 GC + JVM 关闭钩子兜底释放。</li>
     *   <li>{@link HttpTransport} 除 {@code HttpClient} 外无外部资源（无连接池、无线程池、无文件句柄）。</li>
     *   <li>该 {@code HttpClient} 可能由调用方通过 {@link ClobClientBuilder#httpClient(java.net.http.HttpClient)}
     *       传入并在多个 client 间共享，盲目关闭会误杀对方。</li>
     * </ul>
     *
     * <p><b>已预留 {@link AutoCloseable} 是为了：</b>
     * <ol>
     *   <li>JDK 21+ 升级后关闭内部创建（非外部注入）的 {@code HttpClient.close()}；</li>
     *   <li>Plan 4 引入 WebSocket 时关闭订阅连接池与调度线程。</li>
     * </ol>
     *
     * <p>调用 {@code close()} 多次安全；调用后继续访问 {@link #market()} 不会抛出（契约保留至
     * JDK 21 升级）。</p>
     */
    @Override
    public void close() {
        // no-op；详见上方 Javadoc。维持契约直到 JDK 21+ 基线切换（backlog N4）。
    }
}
