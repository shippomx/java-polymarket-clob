package com.polymarket.clob.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.polymarket.clob.exception.ClobSerializationException;

/**
 * SDK 唯一 Jackson 配置出口。所有模块通过 {@link #objectMapper()} 获取共享实例。
 *
 * <p>共享 {@link ObjectMapper} 是线程安全的（Jackson 文档保证，前提是配置完毕后不再修改）。
 * 此类禁止实例化。</p>
 *
 * <h2>反序列化策略</h2>
 * <ul>
 *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES = false}：上游新增字段不破坏向前兼容性</li>
 *   <li>{@code USE_BIG_DECIMAL_FOR_FLOATS = true}：浮点字面量解析为 {@link java.math.BigDecimal}，
 *       避免精度损失（价格/金额类字段是 SDK 的核心敏感数据）</li>
 *   <li>{@code JavaTimeModule}：{@code java.time.*} 的 ISO-8601 序列化</li>
 *   <li>{@code ParameterNamesModule}：配合 Lombok + {@code -parameters} 的数据类反序列化</li>
 * </ul>
 *
 * <h2>异常语义</h2>
 * {@link #readValue} / {@link #writeValue} 的所有失败均封装为
 * {@link ClobSerializationException}，调用方无需直接感知 Jackson 原生异常。
 */
public final class JsonCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new ParameterNamesModule())
            .registerModule(new ClobTypesModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private JsonCodec() {}

    public static ObjectMapper objectMapper() {
        return MAPPER;
    }

    public static <T> T readValue(ObjectMapper mapper, String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new ClobSerializationException("Failed to parse JSON to " + type.getSimpleName(), e);
        }
    }

    public static <T> T readValue(ObjectMapper mapper, String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new ClobSerializationException("Failed to parse JSON to " + type.getType().getTypeName(), e);
        }
    }

    public static String writeValue(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new ClobSerializationException("Failed to serialize value", e);
        }
    }
}
