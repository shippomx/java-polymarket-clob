package com.polymarket.clob.ws.request;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 订阅请求操作动作，对应 Rust {@code Operation}：
 * <ul>
 *   <li>{@link #SUBSCRIBE} —— 订阅</li>
 *   <li>{@link #UNSUBSCRIBE} —— 退订</li>
 * </ul>
 *
 * <p>wire 形态全小写。</p>
 */
public enum Operation {
    SUBSCRIBE,
    UNSUBSCRIBE;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase();
    }
}
