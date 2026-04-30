package com.polymarket.clob.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.clob.exception.ClobApiException;
import com.polymarket.clob.exception.ClobException;
import com.polymarket.clob.exception.ClobSerializationException;
import com.polymarket.clob.exception.ClobTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 所有 REST 调用的统一出口。
 *
 * <p>对外返回 {@link CompletableFuture}，失败以 {@link ClobException} 家族包装：
 * <ul>
 *   <li>非 2xx 响应 → {@link ClobApiException}</li>
 *   <li>连接/超时/TLS/中断等传输层失败 → {@link ClobTransportException}</li>
 *   <li>JSON 序列化或反序列化失败 → {@link ClobSerializationException}</li>
 * </ul>
 * JDK 原生 {@code IOException} / {@code HttpTimeoutException} / {@code InterruptedException}
 * 不会逃逸给调用方，始终被包装成 {@code ClobException} 子类。</p>
 *
 * <p>构造时自动注入默认 {@code User-Agent} / {@code Accept} 头，
 * 避免部分 CDN/WAF 因缺省 UA 限流；调用方传入的同名 header 会覆盖默认值。</p>
 *
 * <p>该类自身线程安全：所有字段 {@code final}，{@link HttpClient} 由 JDK 保证线程安全。</p>
 */
public final class HttpTransport {

    private static final Logger log = LoggerFactory.getLogger(HttpTransport.class);

    /** SDK 默认 {@code User-Agent}，版本从 jar manifest 读取，回退 {@code dev}。 */
    public static final String DEFAULT_USER_AGENT = buildDefaultUserAgent();

    /** SDK 默认 {@code Accept}，始终期望 JSON。 */
    public static final String DEFAULT_ACCEPT = "application/json";

    private final URI baseUri;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Duration requestTimeout;
    /** 每次请求自动带上的 header，调用方可通过同名 header 覆盖。 */
    private final Map<String, String> defaultHeaders;
    /** 出站请求拦截 hook；默认 {@link RequestCaptor#NOOP}，仅 parity 测试场景下注入。 */
    private final RequestCaptor captor;

    public HttpTransport(URI baseUri, HttpClient client, ObjectMapper mapper, Duration requestTimeout) {
        this(baseUri, client, mapper, requestTimeout, defaultHeaders());
    }

    /**
     * 指定默认 header 的构造器，主要给测试用例使用。
     *
     * @param defaultHeaders 每次请求自动带上的 header；传入 {@code null} 等价于空 Map
     */
    public HttpTransport(URI baseUri,
                         HttpClient client,
                         ObjectMapper mapper,
                         Duration requestTimeout,
                         Map<String, String> defaultHeaders) {
        this(baseUri, client, mapper, requestTimeout, defaultHeaders, RequestCaptor.NOOP);
    }

    /**
     * 最长形态构造器，额外接收 {@link RequestCaptor}。
     *
     * @param captor 出站请求拦截 hook；传入 {@code null} 等价于 {@link RequestCaptor#NOOP}
     */
    public HttpTransport(URI baseUri,
                         HttpClient client,
                         ObjectMapper mapper,
                         Duration requestTimeout,
                         Map<String, String> defaultHeaders,
                         RequestCaptor captor) {
        Objects.requireNonNull(baseUri, "baseUri");
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        // 强制 baseUri 以 '/' 结尾，后续 path 拼接行为稳定
        String base = baseUri.toString();
        this.baseUri = base.endsWith("/") ? baseUri : URI.create(base + "/");
        this.client = client;
        this.mapper = mapper;
        this.requestTimeout = requestTimeout;
        this.defaultHeaders = defaultHeaders == null ? Map.of() : Map.copyOf(defaultHeaders);
        this.captor = captor == null ? RequestCaptor.NOOP : captor;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * {@link HttpTransport} 的 fluent builder，主要给注入 {@link RequestCaptor} 的测试链路使用。
     *
     * <p>未显式设置 {@link #defaultHeaders(Map)} 时，沿用 {@link HttpTransport} 默认 UA / Accept
     * 注入；想要彻底关闭默认头的调用方可显式传 {@link Map#of()}。</p>
     */
    public static final class Builder {
        private URI baseUri;
        private HttpClient httpClient;
        private ObjectMapper mapper;
        private Duration requestTimeout = Duration.ofSeconds(10);
        private Map<String, String> defaultHeaders;
        private boolean defaultHeadersSet;
        private RequestCaptor captor = RequestCaptor.NOOP;

        public Builder baseUri(URI v) {
            this.baseUri = v;
            return this;
        }

        public Builder httpClient(HttpClient v) {
            this.httpClient = v;
            return this;
        }

        public Builder objectMapper(ObjectMapper v) {
            this.mapper = v;
            return this;
        }

        public Builder requestTimeout(Duration v) {
            this.requestTimeout = v;
            return this;
        }

        public Builder defaultHeaders(Map<String, String> v) {
            this.defaultHeaders = v;
            this.defaultHeadersSet = true;
            return this;
        }

        public Builder requestCaptor(RequestCaptor v) {
            this.captor = v == null ? RequestCaptor.NOOP : v;
            return this;
        }

        public HttpTransport build() {
            Objects.requireNonNull(baseUri, "baseUri");
            Objects.requireNonNull(httpClient, "httpClient");
            Objects.requireNonNull(mapper, "mapper");
            Map<String, String> headers = defaultHeadersSet ? defaultHeaders : HttpTransport.defaultHeaders();
            return new HttpTransport(baseUri, httpClient, mapper, requestTimeout, headers, captor);
        }
    }

    /**
     * 对 URL path segment 做 percent-encoding，适合拼接含特殊字符的 id。
     *
     * <p>等价于 {@code URLEncoder.encode(s, UTF-8).replace("+", "%20")}：
     * 因为 {@link URLEncoder} 用 form-urlencoded 规则把空格编成 {@code +}，
     * 这在 query 中合法、在 path 中会被当成字面 {@code +}，需要换成 {@code %20}。</p>
     */
    /** 透出持有的 {@link ObjectMapper}，以便 API 层共享同一份配置做预序列化（L2 签名需要）。 */
    public ObjectMapper objectMapper() {
        return mapper;
    }

    public static String pathSegment(String s) {
        Objects.requireNonNull(s, "pathSegment");
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public <R> CompletableFuture<R> get(String path,
                                        Map<String, ?> queryParams,
                                        Map<String, String> headers,
                                        TypeReference<R> responseType) {
        URI url = resolve(path, queryParams);
        if (log.isDebugEnabled()) {
            log.debug("CLOB GET {}", url);
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(url)
                .timeout(requestTimeout)
                .GET();
        applyHeaders(b, headers);
        return send(b.build(), "GET", path, new byte[0], responseType);
    }

    public <R> CompletableFuture<R> delete(String path,
                                           Map<String, ?> queryParams,
                                           Map<String, String> headers,
                                           TypeReference<R> responseType) {
        URI url = resolve(path, queryParams);
        if (log.isDebugEnabled()) {
            log.debug("CLOB DELETE {}", url);
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(url)
                .timeout(requestTimeout)
                .DELETE();
        applyHeaders(b, headers);
        return send(b.build(), "DELETE", path, new byte[0], responseType);
    }

    /**
     * 带 JSON body 的 DELETE。用于 Polymarket {@code /order} / {@code /orders} / {@code /cancel-all}
     * 系列端点：协议语义是"取消"（幂等删除），但取消载荷放在请求体里。
     *
     * <p>JDK {@link HttpRequest.Builder} 的 {@code DELETE()} 不允许带 body，这里用 {@code
     * method("DELETE", publisher)} 的显式形式；部分反向代理/中间件可能会剥离 DELETE 请求体，
     * 在 Polymarket 官方网关上已验证可用。</p>
     *
     * <p>{@code body} 允许为 {@code null}——对应空 body 场景（例如 {@code /cancel-all} 可以是
     * 全空 DELETE）；为保持和 {@link #post(String, Object, Map, TypeReference)} 对称，null 不会
     * 写入 {@code Content-Type} 头。</p>
     *
     * <p>方法名特意与 {@link #delete(String, Map, Map, TypeReference)} 区分：后者把 Map 当 query
     * 参数，前者把对象序列化成 body；若共用 {@code delete} 方法名，{@code Map} 类型实参会被静态
     * 解析到 query 重载，引发难以察觉的错误。</p>
     */
    public <B, R> CompletableFuture<R> deleteWithBody(String path,
                                                      B body,
                                                      Map<String, String> headers,
                                                      TypeReference<R> responseType) {
        String json;
        try {
            json = body == null ? null : mapper.writeValueAsString(body);
        } catch (Exception e) {
            log.warn("CLOB DELETE {} body serialization failed: {}", path, e.getMessage());
            return CompletableFuture.failedFuture(
                    new ClobSerializationException("Failed to serialize DELETE body for " + path, e));
        }
        return deleteRaw(path, json, headers, responseType);
    }

    /**
     * 发送已序列化的 JSON 字符串作为 DELETE body。
     *
     * <p>给 L2 认证链路用：调用方需要先拿到确切的 bytes 做 HMAC，再把同一份 bytes 作为请求体；
     * 如果让 transport 自己再走一遍 Jackson，哪怕逻辑等价，只要 Jackson 配置漂移（例如字段顺序、
     * 空值处理）就会导致签名与请求体不一致。此接口让 API 层掌握"签的就是发的"。</p>
     *
     * <p>{@code json == null} 表示空 body（例如 {@code /cancel-all}），不写 Content-Type。</p>
     */
    public <R> CompletableFuture<R> deleteRaw(String path,
                                              String json,
                                              Map<String, String> headers,
                                              TypeReference<R> responseType) {
        URI url = resolve(path, Map.of());
        if (log.isDebugEnabled()) {
            log.debug("CLOB DELETE {} body={} chars", url, json == null ? 0 : json.length());
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(url)
                .timeout(requestTimeout)
                .method("DELETE",
                        HttpRequest.BodyPublishers.ofString(json == null ? "" : json));
        if (json != null) {
            b.header("Content-Type", "application/json");
        }
        applyHeaders(b, headers);
        byte[] bodyBytes = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
        return send(b.build(), "DELETE", path, bodyBytes, responseType);
    }

    public <B, R> CompletableFuture<R> post(String path,
                                            B body,
                                            Map<String, String> headers,
                                            TypeReference<R> responseType) {
        Objects.requireNonNull(body, "body");
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (Exception e) {
            log.warn("CLOB POST {} body serialization failed: {}", path, e.getMessage());
            return CompletableFuture.failedFuture(
                    new ClobSerializationException("Failed to serialize POST body for " + path, e));
        }
        return postRaw(path, json, headers, responseType);
    }

    /**
     * 发送已序列化的 JSON 字符串作为 POST body。
     *
     * <p>与 {@link #deleteRaw(String, String, Map, TypeReference)} 相同的动机：L2 认证链路要求
     * 签名的 bytes 与发送的 bytes 完全一致，预先序列化可消除两次 Jackson 调用之间任何潜在漂移。</p>
     */
    public <R> CompletableFuture<R> postRaw(String path,
                                            String json,
                                            Map<String, String> headers,
                                            TypeReference<R> responseType) {
        Objects.requireNonNull(json, "json");
        URI url = resolve(path, Map.of());
        if (log.isDebugEnabled()) {
            log.debug("CLOB POST {} body={} chars", url, json.length());
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(url)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        applyHeaders(b, headers);
        byte[] bodyBytes = json.getBytes(StandardCharsets.UTF_8);
        return send(b.build(), "POST", path, bodyBytes, responseType);
    }

    private URI resolve(String path, Map<String, ?> params) {
        // "" 与 "/" 在语义上都表示 baseUri 根，统一剥掉前导 '/'，避免依赖 URI.resolve("") 的隐式行为
        String p = path.startsWith("/") ? path.substring(1) : path;
        URI abs = baseUri.resolve(p);
        String query = QueryEncoder.encode(params);
        if (query.isEmpty()) return abs;
        String sep = abs.getRawQuery() == null ? "?" : "&";
        return URI.create(abs + sep + query);
    }

    private void applyHeaders(HttpRequest.Builder b, Map<String, String> headers) {
        // 先默认 header，再用调用方 header 覆盖；HttpRequest.Builder.header 是追加而非覆盖，
        // 所以对同名 key 先跳过默认值
        Map<String, String> caller = headers == null ? Map.of() : headers;
        for (Map.Entry<String, String> e : defaultHeaders.entrySet()) {
            if (!containsIgnoreCase(caller, e.getKey())) {
                b.header(e.getKey(), e.getValue());
            }
        }
        for (Map.Entry<String, String> e : caller.entrySet()) {
            b.header(e.getKey(), e.getValue());
        }
    }

    private static boolean containsIgnoreCase(Map<String, String> map, String key) {
        for (String k : map.keySet()) {
            if (k.equalsIgnoreCase(key)) return true;
        }
        return false;
    }

    private <R> CompletableFuture<R> send(HttpRequest request,
                                          String method,
                                          String path,
                                          byte[] capturedBody,
                                          TypeReference<R> responseType) {
        // 先把"签的就是发的"那份 bytes 透给 captor，再交给 JDK HttpClient；captor 异常原样冒泡。
        captor.capture(method, request.uri(), request.headers().map(),
                capturedBody == null ? new byte[0] : capturedBody);
        String normalized = path.startsWith("/") ? path : "/" + path;
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((resp, ex) -> {
                    if (ex != null) {
                        Throwable cause = ex;
                        while (cause instanceof CompletionException && cause.getCause() != null) {
                            cause = cause.getCause();
                        }
                        if (cause instanceof ClobException ce) {
                            throw ce;
                        }
                        log.warn("CLOB {} {} transport failure: {}", method, normalized, cause.getMessage());
                        throw new ClobTransportException(
                                "HTTP " + method + " " + normalized + " failed: " + cause.getMessage(),
                                cause);
                    }
                    int status = resp.statusCode();
                    String body = resp.body() == null ? "" : resp.body();
                    if (status < 200 || status >= 300) {
                        // 5xx 升 warn（上游故障），4xx 保留 debug（调用方问题，由上层按需转 error）
                        if (status >= 500) {
                            log.warn("CLOB {} {} -> {} ({} body chars)", method, normalized, status, body.length());
                        } else if (log.isDebugEnabled()) {
                            log.debug("CLOB {} {} -> {} ({} body chars)", method, normalized, status, body.length());
                        }
                        throw new ClobApiException(status, method, normalized, body);
                    }
                    try {
                        return JsonCodec.readValue(mapper, body, responseType);
                    } catch (ClobSerializationException e) {
                        log.warn("CLOB {} {} response parse failed: {}", method, normalized, e.getMessage());
                        throw new ClobSerializationException(
                                "Failed to parse response for " + method + " " + normalized, e.getCause());
                    }
                });
    }

    private static Map<String, String> defaultHeaders() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("User-Agent", DEFAULT_USER_AGENT);
        m.put("Accept", DEFAULT_ACCEPT);
        return m;
    }

    private static String buildDefaultUserAgent() {
        // 优先用 jar manifest 里的 Implementation-Version（构建产物里才有）；
        // IDE / 单测环境下回退到 "dev"
        String version = HttpTransport.class.getPackage().getImplementationVersion();
        return "polymarket-clob-java/" + (version != null && !version.isBlank() ? version : "dev");
    }
}
