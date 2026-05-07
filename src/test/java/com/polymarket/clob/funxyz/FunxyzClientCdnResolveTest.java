package com.polymarket.clob.funxyz;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class FunxyzClientCdnResolveTest {

    private static final Address EOA = Address.fromHex("0x5f0fE47194FAC5FdE131C58359b614F520Db1342");
    private static final Address RECIPIENT = Address.fromHex("0xB51b3627E851EdeaFD81792F012C066805b6dFdE");

    private static final String EOA_OK_BODY = """
            {"depositAddr":"0x4C741213d8519429002ab3E69DE9620fb9b48C69",
             "solanaAddr":"x","tronAddr":"T","btcAddrSegwit":"bc1q",
             "blocked":false}
            """;

    /** 真实抓样的最小化骨架,只保留我们要走的 JSON 路径。 */
    private static final String FLAGS_OK_BODY = """
            {
              "flags": {
                "token_transfer_source_chains_and_assets": {
                  "type": "string",
                  "default_value": "{}",
                  "overrides": [
                    {
                      "if_any": [
                        { "key": "apiKey", "type": "isAnyOf",
                          "values": ["Y53dikxXdT4E3afI1l8BMBSWgyhKvf65k6Dut1k6"] }
                      ],
                      "value": "{}"
                    }
                  ]
                }
              }
            }
            """;

    private WireMockServer server;

    @BeforeEach
    void start() {
        server = new WireMockServer(0);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    /** 给定 stub 与可选超时配置,构造一个走"未显式 apiKey"分支的 client。 */
    private FunxyzClient buildClientWithoutExplicitApiKey(Duration flagsTimeout) {
        FunxyzConfig.Builder b = FunxyzConfig.builder()
                .baseUrl(URI.create(server.baseUrl()))
                .flagsConfigUrl(URI.create(server.baseUrl() + "/flags/v0/config.json"));
        if (flagsTimeout != null) {
            b.flagsRequestTimeout(flagsTimeout);
        }
        // 注意:不调 .apiKey(),保留 null,触发 CDN 路径
        return new FunxyzClient(b.build());
    }

    @Test
    void cdnHappyPath_usesFirstApiKeyFromOverrides() throws Exception {
        server.stubFor(get(urlEqualTo("/flags/v0/config.json"))
                .willReturn(okJson(FLAGS_OK_BODY)));
        server.stubFor(post(urlEqualTo("/v1/eoa")).willReturn(okJson(EOA_OK_BODY)));

        FunxyzClient client = buildClientWithoutExplicitApiKey(null);
        client.getDepositAddresses(EOA, RECIPIENT).get();

        server.verify(getRequestedFor(urlEqualTo("/flags/v0/config.json")));
        server.verify(postRequestedFor(urlEqualTo("/v1/eoa"))
                .withHeader("x-api-key",
                        equalTo("Y53dikxXdT4E3afI1l8BMBSWgyhKvf65k6Dut1k6")));
    }
}
