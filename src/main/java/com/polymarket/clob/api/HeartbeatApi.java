package com.polymarket.clob.api;

import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.heartbeat.HeartbeatResponse;
import com.polymarket.clob.model.Address;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@code POST /v1/heartbeats} 接口，对应 Rust {@code post_heartbeat}。
 *
 * <p>心跳的目的：向服务端证明交易机器人活着，进而维持开放订单的"优先级"不被降级。
 * 服务端期望调用方<strong>以链式方式</strong>使用 heartbeat_id：首次传 {@code null}，
 * 之后每次把上次响应里的 {@code heartbeat_id} 回传。通常每 5~10 秒调用一次即可；
 * 推荐直接使用 {@link com.polymarket.clob.heartbeat.HeartbeatScheduler}
 * 自动调度，无需手动维护 id。</p>
 */
public interface HeartbeatApi {

    /**
     * 发送一次心跳。
     *
     * @param heartbeatId 可为 {@code null}（首次）或上次响应返回的 UUID
     * @return 新的 {@link HeartbeatResponse}，下一次调用应把 {@code heartbeatId()} 传入
     */
    CompletableFuture<HeartbeatResponse> postHeartbeat(
            Address caller,
            ApiCredentials credentials,
            long timestamp,
            UUID heartbeatId);
}
