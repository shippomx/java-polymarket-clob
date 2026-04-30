package com.polymarket.clob.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.auth.L2HeaderBuilder;
import com.polymarket.clob.exception.ClobSerializationException;
import com.polymarket.clob.heartbeat.HeartbeatResponse;
import com.polymarket.clob.http.HttpTransport;
import com.polymarket.clob.model.Address;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@link HeartbeatApi} 的默认实现。
 *
 * <p>关键不变量（同 {@link OrderApiImpl}）：
 * <ul>
 *   <li>body 先由 {@link ObjectMapper} 序列化为字符串，{@link L2HeaderBuilder} 与
 *       {@link HttpTransport#postRaw} 共用同一份 bytes，保证"签的就是发的"。</li>
 *   <li>{@code heartbeatId == null} 时仍然生成 {@code {"heartbeat_id":null}} 对象体
 *       （对齐 Rust {@code json!({"heartbeat_id": heartbeat_id})}），服务端用此区分
 *       "首次握手"与"续签"。</li>
 * </ul>
 * </p>
 */
public final class HeartbeatApiImpl implements HeartbeatApi {

    private static final String HEARTBEAT_PATH = "/v1/heartbeats";

    private final HttpTransport transport;
    private final ObjectMapper mapper;

    public HeartbeatApiImpl(HttpTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.mapper = transport.objectMapper();
    }

    @Override
    public CompletableFuture<HeartbeatResponse> postHeartbeat(Address caller,
                                                              ApiCredentials credentials,
                                                              long timestamp,
                                                              UUID heartbeatId) {
        HeartbeatBody body = new HeartbeatBody(heartbeatId);
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(
                    new ClobSerializationException("Failed to serialize heartbeat body", e));
        }
        Map<String, String> headers = L2HeaderBuilder.build(
                caller, credentials, "POST", HEARTBEAT_PATH, json, timestamp);
        return transport.postRaw("v1/heartbeats", json, headers,
                new TypeReference<HeartbeatResponse>() {});
    }

    /**
     * {@code {"heartbeat_id": <uuid-or-null>}}：必须保留 null 键，且 UUID 以字符串输出。
     */
    static final class HeartbeatBody {
        @JsonProperty("heartbeat_id")
        @JsonInclude(JsonInclude.Include.ALWAYS)
        final UUID heartbeatId;

        HeartbeatBody(UUID heartbeatId) {
            this.heartbeatId = heartbeatId;
        }

        public UUID getHeartbeatId() {
            return heartbeatId;
        }
    }
}
