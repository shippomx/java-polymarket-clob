package com.polymarket.clob.order;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * {@code DELETE /order}, {@code /orders}, {@code /cancel-all} 的统一响应模型。
 *
 * <p>Polymarket 返回的 payload 只有两个关键字段：
 * <ul>
 *   <li>{@code canceled}：成功取消的订单 id 列表；单取消场景也以数组形式返回。</li>
 *   <li>{@code not_canceled}：未取消的 {@code orderId -> reason} 映射。</li>
 * </ul>
 * </p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CancelResponse(
        @JsonProperty("canceled") List<String> canceled,
        @JsonProperty("not_canceled") java.util.Map<String, String> notCanceled) {

    public boolean isFullySuccessful() {
        return notCanceled == null || notCanceled.isEmpty();
    }
}
