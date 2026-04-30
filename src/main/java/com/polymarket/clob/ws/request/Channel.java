package com.polymarket.clob.ws.request;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * WebSocket 订阅 channel 类型，对应 Rust {@code Channel}：
 * <ul>
 *   <li>{@link #MARKET} —— 公共行情（订单簿/价格/tick size/最近成交价）</li>
 *   <li>{@link #USER} —— 认证用户事件（订单/成交）</li>
 * </ul>
 *
 * <p>wire 形态固定为小写：{@code "market"} / {@code "user"}。</p>
 */
public enum Channel {
    MARKET,
    USER;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase();
    }

    /** url 后缀：{@code /ws/market} 或 {@code /ws/user}。 */
    public String pathSegment() {
        return wireValue();
    }
}
