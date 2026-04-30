package com.polymarket.clob.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.polymarket.clob.order.TickSize;

/**
 * {@code GET /tick-size} 响应。
 *
 * <p>上游形态：{@code {"minimum_tick_size": "0.01"}}。字段借助 {@link TickSize#fromWire}
 * 做枚举化，同时接受字符串与数字，避免服务端类型漂移触发 500。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TickSizeResponse(@JsonProperty("minimum_tick_size") TickSize minimumTickSize) {
}
