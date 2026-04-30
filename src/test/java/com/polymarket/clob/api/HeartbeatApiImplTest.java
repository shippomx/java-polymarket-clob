package com.polymarket.clob.api;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.auth.L2HeaderBuilder;
import com.polymarket.clob.heartbeat.HeartbeatResponse;
import com.polymarket.clob.http.HttpTransport;
import com.polymarket.clob.http.JsonCodec;
import com.polymarket.clob.http.SharedHttpClient;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * HeartbeatApiImpl WireMock 集成测试。重点校验：
 * <ul>
 *   <li>首次心跳 body = {@code {"heartbeat_id":null}}；</li>
 *   <li>续签心跳 body = {@code {"heartbeat_id":"<uuid>"}}；</li>
 *   <li>L2 签名 header 出现在请求中，且 Content-Type=application/json。</li>
 * </ul>
 */
class HeartbeatApiImplTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .failOnUnmatchedRequests(true)
            .build();

    private static final ApiCredentials CREDS = new ApiCredentials(
            "00000000-0000-0000-0000-000000000000",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    private static final Address CALLER =
            Address.fromHex("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266");

    private HeartbeatApi api() {
        HttpTransport t = new HttpTransport(
                URI.create(wm.baseUrl() + "/"),
                SharedHttpClient.INSTANCE,
                JsonCodec.objectMapper(),
                Duration.ofSeconds(5));
        return new HeartbeatApiImpl(t);
    }

    @Test
    void firstHeartbeatSendsNullHeartbeatId() {
        UUID returned = UUID.fromString("12345678-1234-1234-1234-123456789abc");

        wm.stubFor(post(urlPathEqualTo("/v1/heartbeats"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader(L2HeaderBuilder.POLY_SIGNATURE, matching(".+"))
                // body 必须包含字面量 null（{"heartbeat_id":null}），不能省略
                .withRequestBody(equalToJson("{\"heartbeat_id\":null}"))
                .willReturn(okJson(
                        "{\"heartbeat_id\":\"" + returned + "\",\"error\":null}")));

        HeartbeatResponse resp = api().postHeartbeat(CALLER, CREDS, 1L, null).join();
        assertThat(resp.heartbeatId()).isEqualTo(returned);
        assertThat(resp.error()).isNull();
    }

    @Test
    void followUpHeartbeatChainsPreviousId() {
        UUID previous = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID next = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        wm.stubFor(post(urlPathEqualTo("/v1/heartbeats"))
                .withRequestBody(equalToJson("{\"heartbeat_id\":\"" + previous + "\"}"))
                .willReturn(okJson(
                        "{\"heartbeat_id\":\"" + next + "\",\"error\":\"\"}")));

        HeartbeatResponse resp = api().postHeartbeat(CALLER, CREDS, 1L, previous).join();
        assertThat(resp.heartbeatId()).isEqualTo(next);
    }
}
