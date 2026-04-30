package com.polymarket.clob.parity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 一次出站请求 / WebSocket 文本 frame 的中性快照。
 *
 * <p>Rust oracle 与 Java parity 测试输出同一 schema，便于按 byte / json 双口径比对。
 * HTTP 与 WS 共用此 record，由 {@code kind} 字段判别字段权威：</p>
 * <ul>
 *   <li>{@code kind="http"}：使用 {@code method} / {@code headers} / {@code body_*}；
 *       {@code ws_channel} 为 {@code null}（被 {@code @JsonInclude(NON_NULL)} 抑制）。</li>
 *   <li>{@code kind="ws"}：使用 {@code ws_channel} / {@code body_text}；
 *       {@code method} / {@code headers} 为 {@code null}（同上抑制）。</li>
 * </ul>
 *
 * <p>{@code body_text} 与 {@code body_b64} 由同一 byte[] 派生：
 * <ul>
 *   <li>文本可见时，{@code body_text} 是权威，{@code body_b64} 仅作字节级 sanity；</li>
 *   <li>未来如有非 UTF-8 二进制 body，{@code body_b64} 是权威。</li>
 * </ul></p>
 *
 * <p>{@code headers} 按大小写不敏感字典序排序（{@link TreeMap} +
 * {@link String#CASE_INSENSITIVE_ORDER}），保证 Java 与 Rust 输出的 JSON key
 * 顺序稳定。Jackson 在 {@code Map} 序列化时按 entry 迭代顺序写出。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"kind", "method", "url", "ws_channel", "headers", "body_text", "body_b64"})
public record ParityRequestSnapshot(
        @JsonProperty("kind") String kind,
        @JsonProperty("method") String method,
        @JsonProperty("url") String url,
        @JsonProperty("ws_channel") String wsChannel,
        @JsonProperty("headers") Map<String, List<String>> headers,
        @JsonProperty("body_text") String bodyText,
        @JsonProperty("body_b64") String bodyB64) {

    /**
     * 构造 HTTP 快照。
     *
     * @param method  HTTP 方法（大写）
     * @param url     完整 URL（含 query string）
     * @param headers 请求头键值对；{@code null} 视作空 map
     * @param body    请求体字节；{@code null} 视作 0 长度
     */
    public static ParityRequestSnapshot http(String method,
                                             String url,
                                             Map<String, List<String>> headers,
                                             byte[] body) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(url, "url");
        byte[] safeBody = body == null ? new byte[0] : body;
        Map<String, List<String>> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            sorted.putAll(headers);
        }
        return new ParityRequestSnapshot(
                "http",
                method,
                url,
                null,
                Collections.unmodifiableMap(sorted),
                new String(safeBody, StandardCharsets.UTF_8),
                Base64.getEncoder().encodeToString(safeBody));
    }

    /**
     * 构造 WebSocket frame 快照。
     *
     * @param channel WS channel（{@code "market"} / {@code "user"}）
     * @param url     连接 URI（含 {@code /ws/market} 或 {@code /ws/user} 后缀）
     * @param body    出帧 JSON 字符串；{@code null} 视作空串
     */
    public static ParityRequestSnapshot ws(String channel, String url, String body) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(url, "url");
        String safe = body == null ? "" : body;
        return new ParityRequestSnapshot(
                "ws",
                null,
                url,
                channel,
                null,
                safe,
                Base64.getEncoder().encodeToString(safe.getBytes(StandardCharsets.UTF_8)));
    }
}
