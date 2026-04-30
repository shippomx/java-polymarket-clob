package com.polymarket.clob.parity;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 跨语言头部对比预处理。
 *
 * <p>Java {@link java.net.http.HttpClient} 与 Rust {@code reqwest} 在 wire 层会自动注入
 * 一些与"代码正确性"无关的 transport 头（{@code Host} / {@code Content-Length} / 自身
 * UA / 协商压缩等）；Track A 只比对"业务 / 认证相关 + {@code Content-Type}"族，
 * 避免 false-positive。</p>
 *
 * <p>归一化策略：</p>
 * <ul>
 *   <li>移除 {@link #DROP}（小写匹配）的所有键</li>
 *   <li>保留余下键的 <strong>原始大小写</strong>（与 wire 一致），不做 lower 化</li>
 *   <li>使用 {@link String#CASE_INSENSITIVE_ORDER} 做 key 排序与比较</li>
 *   <li>{@code null} 输入返回空 map（不抛错），便于 captor 在 NOOP 路径下复用</li>
 * </ul>
 */
public final class HeaderNormalizer {

    /**
     * transport 层自动头，与代码正确性无关，匹配时一律小写。
     *
     * <p>这里包含 IETF HTTP/1.1 与 HTTP/2 通用 hop-by-hop 头，以及 JDK
     * {@code HttpClient} / {@code reqwest} 默认注入的 {@code User-Agent} 与
     * {@code Accept-Encoding}。新加项必须有 spec §5.4 文档支持。</p>
     */
    public static final Set<String> DROP = Set.of(
            "user-agent",
            "accept-encoding",
            "accept-language",
            "host",
            "content-length",
            "connection",
            "te",
            "transfer-encoding"
    );

    private HeaderNormalizer() {}

    /**
     * 返回归一化后的头部 map：丢弃 {@link #DROP}，保留其余键的原始大小写。
     *
     * <p>返回的 map 是新 {@link TreeMap}（CASE_INSENSITIVE_ORDER），每条 value
     * 通过 {@link List#copyOf} 防御外部修改。</p>
     */
    public static Map<String, List<String>> normalize(Map<String, List<String>> raw) {
        Map<String, List<String>> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (raw == null) {
            return out;
        }
        for (Map.Entry<String, List<String>> e : raw.entrySet()) {
            String key = e.getKey();
            if (key == null) {
                continue;
            }
            if (DROP.contains(key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            List<String> v = e.getValue();
            out.put(key, v == null ? List.of() : List.copyOf(v));
        }
        return out;
    }
}
