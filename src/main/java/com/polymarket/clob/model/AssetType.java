package com.polymarket.clob.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 账户余额/授权接口里的资产类型。
 *
 * <p>序列化为全大写常量（与 Rust {@code rename_all = "UPPERCASE"} 对齐）。
 * 反序列化对未知值抛出 {@link IllegalArgumentException}——与 Rust 宽容处理的 {@code Unknown}
 * 变体不同，这里首版选择严格模式；若上游引入新值再加 {@code UNKNOWN} 兼容分支。</p>
 */
public enum AssetType {
    COLLATERAL,
    CONDITIONAL;

    @JsonValue
    public String toJson() {
        return name();
    }

    /** 查询串取值，与 {@link #toJson()} 一致。 */
    public String toQueryValue() {
        return name();
    }

    @JsonCreator
    public static AssetType fromJson(String raw) {
        for (AssetType t : values()) {
            if (t.name().equalsIgnoreCase(raw)) return t;
        }
        throw new IllegalArgumentException("Unknown AssetType: " + raw);
    }
}
