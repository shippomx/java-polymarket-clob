package com.polymarket.clob.api.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;
import java.util.Objects;

/**
 * Trade 状态枚举，wire 上一律大写字符串：{@code MATCHED / MINED / CONFIRMED / RETRYING / FAILED}。
 *
 * <p>未知状态不抛异常，而是记录到 {@link #UNKNOWN} 并把原值保留在 {@link #rawValue()}——
 * 对齐 Rust {@code TradeStatusType::Unknown(String)} 的策略，避免服务端新增 status
 * 时破坏反序列化。</p>
 */
public enum TradeStatusType {
    MATCHED,
    MINED,
    CONFIRMED,
    RETRYING,
    FAILED,
    /** 兜底：上游返回非预期值时保留原始文本。只在反序列化路径使用，不允许直接构造。 */
    UNKNOWN;

    private String rawValue;

    /** 原始 wire value；对 {@link #UNKNOWN} 以外的成员等同 {@link #name()}。 */
    public String rawValue() {
        return rawValue == null ? name() : rawValue;
    }

    @JsonValue
    public String wireName() {
        return rawValue();
    }

    @JsonCreator
    public static TradeStatusType fromWire(String value) {
        Objects.requireNonNull(value, "trade status");
        String upper = value.trim().toUpperCase(Locale.ROOT);
        for (TradeStatusType t : values()) {
            if (t != UNKNOWN && t.name().equals(upper)) return t;
        }
        TradeStatusType unknown = UNKNOWN;
        unknown.rawValue = value;
        return unknown;
    }
}
