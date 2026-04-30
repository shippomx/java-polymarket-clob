package com.polymarket.clob.order;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Objects;

/**
 * 订单类型。序列化为大写字符串（{@code "GTC" / "GTD" / "FOK" / "FAK"}），反序列化大小写不敏感，
 * 与 Polymarket CLOB 上游、py-clob-client 的 {@code OrderType} 枚举保持字节一致。
 *
 * <ul>
 *   <li>{@link #GTC} — Good-Til-Cancelled，挂单直至成交或取消（限价单默认）。</li>
 *   <li>{@link #GTD} — Good-Til-Date，到 {@code expiration} 自动过期（限价单 + 定时）。</li>
 *   <li>{@link #FOK} — Fill-Or-Kill，立即完全成交或整体取消（市价单）。</li>
 *   <li>{@link #FAK} — Fill-And-Kill，立即成交可用部分，其余取消（市价单）。</li>
 * </ul>
 */
public enum OrderType {
    GTC,
    GTD,
    FOK,
    FAK;

    /** 序列化输出始终大写，和 Python/TS 客户端一致。 */
    @JsonValue
    public String wireName() {
        return name();
    }

    /**
     * 大小写不敏感地反序列化；未知值直接抛 {@link IllegalArgumentException}
     * 让 Jackson 包成 {@link com.fasterxml.jackson.databind.exc.InvalidFormatException}。
     */
    @JsonCreator
    public static OrderType fromWire(String value) {
        Objects.requireNonNull(value, "orderType");
        return OrderType.valueOf(value.trim().toUpperCase());
    }

    /** 限价单：挂单类型（GTC / GTD），允许 {@code postOnly=true}。 */
    public boolean isLimit() {
        return this == GTC || this == GTD;
    }

    /** 市价单：即时成交类型（FOK / FAK），不得 {@code postOnly=true}。 */
    public boolean isMarket() {
        return this == FOK || this == FAK;
    }
}
