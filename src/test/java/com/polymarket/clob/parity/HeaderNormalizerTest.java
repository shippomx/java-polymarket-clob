package com.polymarket.clob.parity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 {@link HeaderNormalizer} 按 spec §5.4 的策略丢弃 transport 层自动头，
 * 保留业务 / 认证相关头（含 {@code Content-Type}）。
 */
class HeaderNormalizerTest {

    @Test
    void dropsRuntimeOnlyHeaders() {
        Map<String, List<String>> raw = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        raw.put("User-Agent", List.of("polymarket-clob-java/dev"));
        raw.put("Accept-Encoding", List.of("gzip, deflate"));
        raw.put("Host", List.of("clob.polymarket.com"));
        raw.put("Content-Length", List.of("42"));
        raw.put("POLY_ADDRESS", List.of("0xdead"));
        raw.put("Content-Type", List.of("application/json"));

        Map<String, List<String>> norm = HeaderNormalizer.normalize(raw);

        assertThat(norm.keySet())
                .containsExactlyInAnyOrder("POLY_ADDRESS", "Content-Type");
    }

    @Test
    void preservesMixedCaseKeysButComparesCaseInsensitive() {
        Map<String, List<String>> raw = Map.of(
                "POLY_ADDRESS", List.of("0xabc"),
                "poly_signature", List.of("0xdef"));
        Map<String, List<String>> norm = HeaderNormalizer.normalize(raw);
        assertThat(norm.get("POLY_ADDRESS")).containsExactly("0xabc");
        assertThat(norm.get("poly_signature")).containsExactly("0xdef");
    }

    @Test
    void caseInsensitiveDropMatching() {
        // wire 层各种大小写都应该被 DROP 集合命中（小写匹配）
        Map<String, List<String>> raw = Map.of(
                "user-agent", List.of("x"),
                "Accept-Encoding", List.of("y"),
                "HOST", List.of("z"),
                "POLY_TIMESTAMP", List.of("123"));
        Map<String, List<String>> norm = HeaderNormalizer.normalize(raw);
        assertThat(norm.keySet()).containsExactly("POLY_TIMESTAMP");
    }

    @Test
    void nullInputReturnsEmptyMap() {
        assertThat(HeaderNormalizer.normalize(null)).isEmpty();
    }
}
