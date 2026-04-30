package com.polymarket.clob.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.polymarket.clob.http.JsonCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端烟雾测试：input JSON → ParityFixtureDispatcher → SDK 调用 →
 * RequestCaptor → ParityRequestSnapshot。
 *
 * <p>本 task 仅覆盖 {@code get_ok} kind（spec §7.2 列表的第一项，最简单的 GET /
 * 健康检查），目标是验证整条管道接通。其他 kind 在 Task 13/14 增补。</p>
 */
class ParityFixtureDispatcherSmokeTest {

    private WireMockServer wm;

    @BeforeEach
    void up() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        wm.stubFor(get(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(200).withBody("OK")));
    }

    @AfterEach
    void down() {
        if (wm != null) wm.stop();
    }

    @Test
    void dispatchesGetOkAndCapturesSnapshot() throws Exception {
        String inputJson = """
                {
                  "id": "001-rest-get-ok-baseline",
                  "category": "rest-public",
                  "frozen": {"timestamp": 1700000000, "salt": "0x2a", "nonce": 0},
                  "config": {
                    "chain_id": "POLYGON",
                    "signature_type": "EOA",
                    "host": "%s"
                  },
                  "call": {"kind": "get_ok", "args": {}}
                }""".formatted(wm.baseUrl());

        JsonNode input = JsonCodec.objectMapper().readTree(inputJson);
        ParityRequestSnapshot snap =
                ParityFixtureDispatcher.dispatchAndCapture(input).join();

        assertThat(snap.kind()).isEqualTo("http");
        assertThat(snap.method()).isEqualTo("GET");
        assertThat(snap.url()).endsWith("/");
        assertThat(snap.headers()).doesNotContainKey("POLY_ADDRESS");
        assertThat(snap.headers()).doesNotContainKey("POLY_SIGNATURE");
        assertThat(snap.bodyText()).isEmpty();
    }

    @Test
    void unsupportedKindFailsExceptionally() {
        String inputJson = """
                {
                  "id": "999-unsupported",
                  "category": "rest-public",
                  "frozen": {"timestamp": 1700000000, "salt": "0x2a", "nonce": 0},
                  "config": {
                    "chain_id": "POLYGON",
                    "signature_type": "EOA",
                    "host": "%s"
                  },
                  "call": {"kind": "post_limit_order", "args": {}}
                }""".formatted(wm.baseUrl());

        JsonNode input;
        try {
            input = JsonCodec.objectMapper().readTree(inputJson);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertThat(ParityFixtureDispatcher.dispatchAndCapture(input))
                .failsWithin(java.time.Duration.ofSeconds(2));
    }
}
