package com.polymarket.clob.http;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringJoiner;

/**
 * URL 查询串编码，对齐 Rust {@code serde_html_form}：
 * <ul>
 *   <li>{@code Iterable} 值用重复 key 展开（{@code key=v1&key=v2}）</li>
 *   <li>{@code null} 值整条跳过；集合内的 {@code null} 元素也跳过</li>
 *   <li>空 Map 返回空串，不带 {@code ?}，由调用方拼接</li>
 *   <li>key/value 使用 {@code application/x-www-form-urlencoded} 规则编码（空格 → {@code +}）</li>
 * </ul>
 *
 * <p>为保持编码顺序稳定，调用方应传入有序 Map（例如 {@link java.util.LinkedHashMap}）。
 * {@code Map.of(...)} 在 Java 17 中顺序不保证。</p>
 */
public final class QueryEncoder {

    private QueryEncoder() {}

    public static String encode(Map<String, ?> params) {
        if (params == null || params.isEmpty()) return "";
        StringJoiner sj = new StringJoiner("&");
        for (Map.Entry<String, ?> e : params.entrySet()) {
            Object value = e.getValue();
            if (value == null) continue;
            String k = urlEncode(e.getKey());
            if (value instanceof Iterable<?> it) {
                for (Object v : it) {
                    if (v == null) continue;
                    sj.add(k + "=" + urlEncode(String.valueOf(v)));
                }
            } else {
                sj.add(k + "=" + urlEncode(String.valueOf(value)));
            }
        }
        return sj.toString();
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
