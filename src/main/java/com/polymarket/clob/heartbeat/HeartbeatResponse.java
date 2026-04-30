package com.polymarket.clob.heartbeat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * {@code POST /v1/heartbeats} 的响应，对应 Rust {@code HeartbeatResponse}。
 *
 * <p>服务端返回新的 {@code heartbeat_id}（UUID），客户端下一次调用时需要把它回传，
 * 形成"心跳链"以证明会话活性；配合 {@link com.polymarket.clob.heartbeat.HeartbeatScheduler}
 * 自动续签。</p>
 *
 * <p>{@code error} 非空表示上游接受了本次心跳但提示需要注意的异常（例如 token 即将过期）。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HeartbeatResponse(
        @JsonProperty("heartbeat_id") UUID heartbeatId,
        @JsonProperty("error") String error) {
}
