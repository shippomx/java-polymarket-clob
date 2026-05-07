package com.polymarket.clob.auth;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.polymarket.clob.exception.ClobAuthException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BuilderHeaderBuilder 单元测试。
 *
 * <ul>
 *   <li>{@link BuilderConfig.Local} 分支：对比手写 HMAC，保证 {@link L2HeaderBuilder#hmac} 复用正确。</li>
 *   <li>{@link BuilderConfig.Remote} 分支：WireMock 模拟远程 signing 服务器；
 *       校验入参（method/path/body/timestamp）与出参（POLY_BUILDER_*）。</li>
 *   <li>Remote 返回非 2xx / JSON 残缺 → {@link ClobAuthException}。</li>
 * </ul>
 */
class BuilderHeaderBuilderTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .failOnUnmatchedRequests(true)
            .build();

    private static final ApiCredentials CREDS = new ApiCredentials(
            "builder-api-key",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "builder-passphrase");

    @Test
    void localConfigBuildsFourHeadersWithHmac() {
        BuilderHeaderBuilder b = new BuilderHeaderBuilder(BuilderConfig.local(CREDS));
        long ts = 1700000000L;
        Map<String, String> headers =
                b.build("GET", "/builder/trades", "", ts).join();

        String expectedSig = L2HeaderBuilder.hmac(CREDS.secret(), ts + "GET" + "/builder/trades" + "");
        assertThat(headers)
                .containsEntry(BuilderHeaderBuilder.POLY_BUILDER_API_KEY, "builder-api-key")
                .containsEntry(BuilderHeaderBuilder.POLY_BUILDER_PASSPHRASE, "builder-passphrase")
                .containsEntry(BuilderHeaderBuilder.POLY_BUILDER_SIGNATURE, expectedSig)
                .containsEntry(BuilderHeaderBuilder.POLY_BUILDER_TIMESTAMP, Long.toString(ts));
    }

    @Test
    void localConfigSubstitutesSingleQuotesInBodyLikeL2() {
        // 与 L2HeaderBuilder 一致：' → "，保证 Python/Rust 的 json.dumps 与 json!() 一致
        BuilderHeaderBuilder b = new BuilderHeaderBuilder(BuilderConfig.local(CREDS));
        Map<String, String> withApostrophe =
                b.build("POST", "/x", "{'a':1}", 1L).join();
        Map<String, String> withQuote =
                b.build("POST", "/x", "{\"a\":1}", 1L).join();
        assertThat(withApostrophe.get(BuilderHeaderBuilder.POLY_BUILDER_SIGNATURE))
                .isEqualTo(withQuote.get(BuilderHeaderBuilder.POLY_BUILDER_SIGNATURE));
    }

    @Test
    void remoteConfigForwardsRequestAndParsesResponse() {
        wm.stubFor(post(urlPathEqualTo("/sign"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("Authorization", equalTo("Bearer remote-token"))
                .withRequestBody(matchingJsonPath("$.method", equalTo("GET")))
                .withRequestBody(matchingJsonPath("$.path", equalTo("/builder/trades")))
                .withRequestBody(matchingJsonPath("$.body", equalTo("")))
                .withRequestBody(matchingJsonPath("$.timestamp", equalTo("1700000000")))
                .willReturn(okJson("""
                        {"POLY_BUILDER_API_KEY":"remote-key",
                         "POLY_BUILDER_PASSPHRASE":"remote-pass",
                         "POLY_BUILDER_SIGNATURE":"remote-sig",
                         "POLY_BUILDER_TIMESTAMP":"1700000000"}
                        """)));

        BuilderConfig remote = BuilderConfig.remote(wm.baseUrl() + "/sign", "remote-token");
        BuilderHeaderBuilder b = new BuilderHeaderBuilder(remote);
        Map<String, String> headers =
                b.build("GET", "/builder/trades", "", 1_700_000_000L).join();

        assertThat(headers).containsEntry(BuilderHeaderBuilder.POLY_BUILDER_API_KEY, "remote-key")
                .containsEntry(BuilderHeaderBuilder.POLY_BUILDER_SIGNATURE, "remote-sig")
                .containsEntry(BuilderHeaderBuilder.POLY_BUILDER_TIMESTAMP, "1700000000");
    }

    @Test
    void remoteConfigWithoutTokenOmitsAuthorization() {
        wm.stubFor(post(urlPathEqualTo("/sign"))
                .withHeader("Authorization", absent())
                .willReturn(okJson("""
                        {"POLY_BUILDER_API_KEY":"k","POLY_BUILDER_PASSPHRASE":"p",
                         "POLY_BUILDER_SIGNATURE":"s","POLY_BUILDER_TIMESTAMP":"1"}
                        """)));

        BuilderConfig remote = BuilderConfig.remote(wm.baseUrl() + "/sign", null);
        BuilderHeaderBuilder b = new BuilderHeaderBuilder(remote);
        assertThat(b.build("GET", "/x", "", 1L).join())
                .containsKey(BuilderHeaderBuilder.POLY_BUILDER_API_KEY);
    }

    @Test
    void remoteConfig5xxIsWrappedAsAuthException() {
        wm.stubFor(post(urlPathEqualTo("/sign"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        BuilderHeaderBuilder b = new BuilderHeaderBuilder(
                BuilderConfig.remote(wm.baseUrl() + "/sign", null));
        assertThatThrownBy(() -> b.build("GET", "/x", "", 1L).join())
                .hasCauseInstanceOf(ClobAuthException.class)
                .hasMessageContaining("status 500");
    }

    @Test
    void remoteConfigMissingRequiredFieldIsWrappedAsAuthException() {
        wm.stubFor(post(urlPathEqualTo("/sign"))
                // 缺 POLY_BUILDER_SIGNATURE 字段
                .willReturn(okJson("""
                        {"POLY_BUILDER_API_KEY":"k","POLY_BUILDER_TIMESTAMP":"1"}
                        """)));

        BuilderHeaderBuilder b = new BuilderHeaderBuilder(
                BuilderConfig.remote(wm.baseUrl() + "/sign", null));
        assertThatThrownBy(() -> b.build("GET", "/x", "", 1L).join())
                .hasCauseInstanceOf(ClobAuthException.class)
                .hasMessageContaining("missing required fields");
    }
}
