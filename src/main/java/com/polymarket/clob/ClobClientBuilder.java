package com.polymarket.clob;

import com.polymarket.clob.http.HttpTransport;
import com.polymarket.clob.http.JsonCodec;
import com.polymarket.clob.http.RequestCaptor;
import com.polymarket.clob.order.SaltSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * {@link ClobClient} 的 fluent builder。
 *
 * <p>典型用法：
 * <pre>{@code
 * ClobClient client = ClobClient.builder()
 *         .useDefaultEndpoint()
 *         .chainId(ChainId.POLYGON)
 *         .build();
 * }</pre></p>
 *
 * <p>该类非线程安全，应仅在单线程初始化阶段使用。</p>
 */
public final class ClobClientBuilder {

    /** Polymarket 生产 CLOB 端点。 */
    public static final String DEFAULT_ENDPOINT = "https://clob.polymarket.com";

    /** 默认请求超时。 */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private URI endpoint;
    private Long chainId;
    private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
    private HttpClient httpClient;
    private Clock clock;
    private SaltSource saltSource;
    private RequestCaptor requestCaptor;

    ClobClientBuilder() {}

    /**
     * 以字符串形式设置端点。
     *
     * <p>与 {@link #endpoint(URI)} / {@link #useDefaultEndpoint()} 语义为"最后一次调用胜"。</p>
     */
    public ClobClientBuilder endpoint(String url) {
        Objects.requireNonNull(url, "url");
        this.endpoint = URI.create(url);
        return this;
    }

    /**
     * 以 {@link URI} 设置端点。
     *
     * <p>与 {@link #endpoint(String)} / {@link #useDefaultEndpoint()} 语义为"最后一次调用胜"。</p>
     */
    public ClobClientBuilder endpoint(URI uri) {
        Objects.requireNonNull(uri, "uri");
        this.endpoint = uri;
        return this;
    }

    /**
     * 使用默认生产端点 {@value #DEFAULT_ENDPOINT}。
     *
     * <p>与两个 {@code endpoint(...)} 重载语义为"最后一次调用胜"。</p>
     */
    public ClobClientBuilder useDefaultEndpoint() {
        this.endpoint = URI.create(DEFAULT_ENDPOINT);
        return this;
    }

    public ClobClientBuilder chainId(long chainId) {
        this.chainId = chainId;
        return this;
    }

    public ClobClientBuilder requestTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        this.requestTimeout = timeout;
        return this;
    }

    /**
     * 注入自定义 {@link HttpClient}。
     *
     * <p>传入 {@code null} 等同于不调用此方法，{@link #build()} 时会
     * 回退到 {@link HttpClient#newHttpClient()}。</p>
     */
    public ClobClientBuilder httpClient(HttpClient client) {
        this.httpClient = client;
        return this;
    }

    /**
     * 注入自定义 {@link Clock}，主要用于 parity 测试固定时间戳产出可重现签名。
     * 传入 {@code null} 等同于不调用，{@link #build()} 时回落到 {@link Clock#systemUTC()}。
     */
    public ClobClientBuilder clock(Clock clock) {
        this.clock = clock;
        return this;
    }

    /**
     * 注入自定义 {@link SaltSource}，主要用于 parity 测试固定 salt 产出 golden 向量。
     * 传入 {@code null} 等同于不调用，{@link #build()} 时回落到 {@link SaltSource#secureRandom()}。
     */
    public ClobClientBuilder saltSource(SaltSource saltSource) {
        this.saltSource = saltSource;
        return this;
    }

    /**
     * 注入 {@link RequestCaptor}，主要用于 parity 测试在 wire 层抓取 outbound HTTP 请求。
     *
     * <p>captor 直接透传给内部 {@link HttpTransport}，对生产路径零开销
     * （默认 {@link RequestCaptor#NOOP} 是空 lambda）。传入 {@code null} 等同于不调用。</p>
     */
    public ClobClientBuilder requestCaptor(RequestCaptor captor) {
        this.requestCaptor = captor;
        return this;
    }

    public ClobClient build() {
        if (endpoint == null) {
            throw new IllegalStateException(
                    "endpoint is required (call .endpoint(...) or .useDefaultEndpoint())");
        }
        if (chainId == null) {
            throw new IllegalStateException(
                    "chainId is required (use ChainId.POLYGON or ChainId.AMOY)");
        }
        validateEndpoint(endpoint);
        HttpClient client = httpClient != null ? httpClient : HttpClient.newHttpClient();
        HttpTransport.Builder tb = HttpTransport.builder()
                .baseUri(endpoint)
                .httpClient(client)
                .objectMapper(JsonCodec.objectMapper())
                .requestTimeout(requestTimeout);
        if (requestCaptor != null) {
            tb.requestCaptor(requestCaptor);
        }
        HttpTransport transport = tb.build();
        Clock effectiveClock = clock != null ? clock : Clock.systemUTC();
        SaltSource effectiveSalt = saltSource != null ? saltSource : SaltSource.secureRandom();
        return new ClobClient(endpoint, chainId, transport, effectiveClock, effectiveSalt);
    }

    private static void validateEndpoint(URI uri) {
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException(
                    "endpoint must be absolute (scheme + authority), got: " + uri);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "endpoint scheme must be http or https, got: " + scheme);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException(
                    "endpoint must have a host, got: " + uri);
        }
    }
}
