package com.polymarket.clob.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.clob.exception.ClobAuthException;
import com.polymarket.clob.exception.ClobSerializationException;
import com.polymarket.clob.http.JsonCodec;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 构造 Builder 扩展头：{@code POLY_BUILDER_API_KEY} / {@code POLY_BUILDER_PASSPHRASE} /
 * {@code POLY_BUILDER_SIGNATURE} / {@code POLY_BUILDER_TIMESTAMP}。
 *
 * <p>对齐 Rust {@code auth::builder::Builder::create_headers}：
 * <ul>
 *   <li>{@link BuilderConfig.Local}：本地 HMAC，签同一条 {@code timestamp + method + path + body} 消息。</li>
 *   <li>{@link BuilderConfig.Remote}：转发 POST 给远程 signing 服务，body 形如
 *       {@code {"method","path","body","timestamp"}}；响应是 4 个 {@code POLY_BUILDER_*} 字段。</li>
 * </ul>
 * </p>
 *
 * <p>Remote 路径使用 JDK 原生 {@link HttpClient} 发送 HTTP 请求；默认 10 秒超时。
 * 失败全部包装为 {@link ClobAuthException}（授权/网络/反序列化错误均视为认证失败）。</p>
 */
public final class BuilderHeaderBuilder {

    public static final String POLY_BUILDER_API_KEY = "POLY_BUILDER_API_KEY";
    public static final String POLY_BUILDER_PASSPHRASE = "POLY_BUILDER_PASSPHRASE";
    public static final String POLY_BUILDER_SIGNATURE = "POLY_BUILDER_SIGNATURE";
    public static final String POLY_BUILDER_TIMESTAMP = "POLY_BUILDER_TIMESTAMP";

    private static final Duration DEFAULT_REMOTE_TIMEOUT = Duration.ofSeconds(10);

    private final BuilderConfig config;
    private final HttpClient remoteClient;
    private final ObjectMapper mapper;
    private final Duration remoteTimeout;

    public BuilderHeaderBuilder(BuilderConfig config) {
        this(config, null, null, null);
    }

    /**
     * 高级构造：允许注入共享 {@link HttpClient} / {@link ObjectMapper} / 自定义超时。
     * 主要给测试以及重度调用方复用 transport 资源时使用；null 字段自动回退默认值。
     */
    public BuilderHeaderBuilder(BuilderConfig config,
                                HttpClient remoteClient,
                                ObjectMapper mapper,
                                Duration remoteTimeout) {
        this.config = Objects.requireNonNull(config, "config");
        this.remoteClient = remoteClient == null
                ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                : remoteClient;
        this.mapper = mapper == null ? JsonCodec.objectMapper() : mapper;
        this.remoteTimeout = remoteTimeout == null ? DEFAULT_REMOTE_TIMEOUT : remoteTimeout;
    }

    public BuilderConfig config() {
        return config;
    }

    /**
     * 同步生成 Builder 头。
     *
     * @param method    HTTP method（大写）
     * @param path      请求 path（与 L2 签名一致）
     * @param body      请求体；空 body 传 {@code ""}
     * @param timestamp Unix 秒
     */
    public CompletableFuture<Map<String, String>> build(String method,
                                                        String path,
                                                        String body,
                                                        long timestamp) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        String safeBody = body == null ? "" : body.replace('\'', '"');
        if (config instanceof BuilderConfig.Local local) {
            return CompletableFuture.completedFuture(
                    buildLocal(local.credentials(), method, path, safeBody, timestamp));
        }
        if (config instanceof BuilderConfig.Remote remote) {
            return buildRemote(remote, method, path, safeBody, timestamp);
        }
        // sealed 保证不会走到：防御性校验
        throw new IllegalStateException(
                "Unknown BuilderConfig variant: " + config.getClass().getName());
    }

    private static Map<String, String> buildLocal(ApiCredentials credentials,
                                                  String method,
                                                  String path,
                                                  String body,
                                                  long timestamp) {
        String message = timestamp + method + path + body;
        String signature = L2HeaderBuilder.hmac(credentials.secret(), message);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(POLY_BUILDER_API_KEY, credentials.apiKey());
        headers.put(POLY_BUILDER_PASSPHRASE, credentials.passphrase());
        headers.put(POLY_BUILDER_SIGNATURE, signature);
        headers.put(POLY_BUILDER_TIMESTAMP, Long.toString(timestamp));
        return headers;
    }

    private CompletableFuture<Map<String, String>> buildRemote(BuilderConfig.Remote remote,
                                                               String method,
                                                               String path,
                                                               String body,
                                                               long timestamp) {
        RemoteRequest payload = new RemoteRequest(method, path, body, timestamp);
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(
                    new ClobSerializationException("Failed to serialize Builder remote request", e));
        }
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(remote.host())
                .timeout(remoteTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        remote.token().filter(t -> !t.isBlank())
                .ifPresent(t -> reqBuilder.header("Authorization", "Bearer " + t));

        return remoteClient.sendAsync(reqBuilder.build(), HttpResponse.BodyHandlers.ofString())
                .handle((resp, err) -> {
                    if (err != null) {
                        throw new ClobAuthException(
                                "Remote Builder signing failed: " + err.getMessage(), err);
                    }
                    int status = resp.statusCode();
                    if (status < 200 || status >= 300) {
                        throw new ClobAuthException(
                                "Remote Builder signing rejected with status " + status + ": " + resp.body());
                    }
                    RemoteResponse parsed;
                    try {
                        parsed = JsonCodec.readValue(mapper, resp.body(),
                                new TypeReference<RemoteResponse>() {});
                    } catch (Exception e) {
                        throw new ClobAuthException("Failed to parse remote Builder response", e);
                    }
                    if (parsed == null || parsed.poly_builder_api_key == null
                            || parsed.poly_builder_signature == null) {
                        throw new ClobAuthException(
                                "Remote Builder response missing required fields: " + resp.body());
                    }
                    Map<String, String> headers = new LinkedHashMap<>();
                    headers.put(POLY_BUILDER_API_KEY, parsed.poly_builder_api_key);
                    headers.put(POLY_BUILDER_PASSPHRASE,
                            parsed.poly_builder_passphrase == null ? "" : parsed.poly_builder_passphrase);
                    headers.put(POLY_BUILDER_SIGNATURE, parsed.poly_builder_signature);
                    headers.put(POLY_BUILDER_TIMESTAMP, parsed.poly_builder_timestamp);
                    return headers;
                });
    }

    /** 远程 signing 服务器的入参；字段名与 Rust {@code json!({ method, path, body, timestamp })} 一致。 */
    static final class RemoteRequest {
        @JsonProperty("method") final String method;
        @JsonProperty("path") final String path;
        @JsonProperty("body") final String body;
        @JsonProperty("timestamp") final long timestamp;

        RemoteRequest(String method, String path, String body, long timestamp) {
            this.method = method;
            this.path = path;
            this.body = body;
            this.timestamp = timestamp;
        }

        public String getMethod() { return method; }
        public String getPath() { return path; }
        public String getBody() { return body; }
        public long getTimestamp() { return timestamp; }
    }

    /**
     * 远程 signing 响应。Rust 使用 {@code rename_all = "UPPERCASE"}，字段是大写 snake_case；
     * 映射为 Java 时直接用同名字段接收（Jackson 按字段名大小写敏感，刚好对齐 wire）。
     */
    static final class RemoteResponse {
        @JsonProperty("POLY_BUILDER_API_KEY") final String poly_builder_api_key;
        @JsonProperty("POLY_BUILDER_TIMESTAMP") final String poly_builder_timestamp;
        @JsonProperty("POLY_BUILDER_PASSPHRASE") final String poly_builder_passphrase;
        @JsonProperty("POLY_BUILDER_SIGNATURE") final String poly_builder_signature;

        @JsonCreator
        RemoteResponse(
                @JsonProperty("POLY_BUILDER_API_KEY") String apiKey,
                @JsonProperty("POLY_BUILDER_TIMESTAMP") String timestamp,
                @JsonProperty("POLY_BUILDER_PASSPHRASE") String passphrase,
                @JsonProperty("POLY_BUILDER_SIGNATURE") String signature) {
            this.poly_builder_api_key = apiKey;
            this.poly_builder_timestamp = timestamp;
            this.poly_builder_passphrase = passphrase;
            this.poly_builder_signature = signature;
        }
    }
}
