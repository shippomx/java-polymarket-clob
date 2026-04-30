package com.polymarket.clob.api.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;
import java.util.Objects;

/**
 * 交易记录中标识当前用户所处角色：{@code TAKER} 或 {@code MAKER}。
 * 对应 Rust {@code TraderSide}。wire 为全大写。
 */
public enum TraderSide {
    TAKER,
    MAKER;

    @JsonValue
    public String wireName() {
        return name();
    }

    @JsonCreator
    public static TraderSide fromWire(String value) {
        Objects.requireNonNull(value, "traderSide");
        return TraderSide.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
