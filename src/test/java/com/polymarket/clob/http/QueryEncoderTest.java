package com.polymarket.clob.http;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QueryEncoderTest {

    @Test
    void emptyMapReturnsEmptyString() {
        assertThat(QueryEncoder.encode(Map.of())).isEmpty();
    }

    @Test
    void nullMapReturnsEmptyString() {
        assertThat(QueryEncoder.encode(null)).isEmpty();
    }

    @Test
    void singleScalarEncodes() {
        assertThat(QueryEncoder.encode(Map.of("token_id", "42")))
                .isEqualTo("token_id=42");
    }

    @Test
    void multipleScalarsJoinedWithAmpersand() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token_id", "42");
        m.put("side", "BUY");
        assertThat(QueryEncoder.encode(m)).isEqualTo("token_id=42&side=BUY");
    }

    @Test
    void iterableUsesRepeatedKey() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ids", List.of("a", "b", "c"));
        assertThat(QueryEncoder.encode(m)).isEqualTo("ids=a&ids=b&ids=c");
    }

    @Test
    void urlEncodesSpecialChars() {
        assertThat(QueryEncoder.encode(Map.of("q", "hello world&ok")))
                .isEqualTo("q=hello+world%26ok");
    }

    @Test
    void nullValueIsSkipped() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("a", "1");
        m.put("b", null);
        m.put("c", "3");
        assertThat(QueryEncoder.encode(m)).isEqualTo("a=1&c=3");
    }

    @Test
    void nullInsideIterableIsSkipped() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ids", Arrays.asList("a", null, "c"));
        assertThat(QueryEncoder.encode(m)).isEqualTo("ids=a&ids=c");
    }

    @Test
    void emptyIterableYieldsNoPair() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ids", List.of());
        m.put("x", "y");
        assertThat(QueryEncoder.encode(m)).isEqualTo("x=y");
    }

    @Test
    void nonStringScalarUsesToString() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("limit", 100);
        m.put("rate", new java.math.BigDecimal("0.01"));
        assertThat(QueryEncoder.encode(m)).isEqualTo("limit=100&rate=0.01");
    }

    @Test
    void keysAreAlsoUrlEncoded() {
        assertThat(QueryEncoder.encode(Map.of("a b", "c")))
                .isEqualTo("a+b=c");
    }
}
