package com.polymarket.clob.api;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.polymarket.clob.api.model.BuilderApiKeyResponse;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.auth.L2HeaderBuilder;
import com.polymarket.clob.http.HttpTransport;
import com.polymarket.clob.http.JsonCodec;
import com.polymarket.clob.http.SharedHttpClient;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * BuilderApiImpl WireMock 集成测试。覆盖 {@code /auth/builder-api-key} 三个端点：
 * create / list / revoke，并校验 builder 头是否正确合并到出站请求。
 */
class BuilderApiImplTest {

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
    private static final Map<String, String> BUILDER_HEADERS = Map.of(
            "POLY_BUILDER_API_KEY", "builder-key",
            "POLY_BUILDER_PASSPHRASE", "builder-pass",
            "POLY_BUILDER_SIGNATURE", "builder-sig",
            "POLY_BUILDER_TIMESTAMP", "1");

    private BuilderApi api() {
        HttpTransport t = new HttpTransport(
                URI.create(wm.baseUrl() + "/"),
                SharedHttpClient.INSTANCE,
                JsonCodec.objectMapper(),
                Duration.ofSeconds(5));
        return new BuilderApiImpl(t);
    }

    @Test
    void createBuilderApiKeyReturnsNewCredentials() {
        wm.stubFor(post(urlPathEqualTo("/auth/builder-api-key"))
                .withHeader(L2HeaderBuilder.POLY_SIGNATURE, matching(".+"))
                // create 不应携带 Builder 头，仅 L2 凭证
                .withHeader("POLY_BUILDER_API_KEY", absent())
                .willReturn(okJson("""
                        {"apiKey":"11111111-1111-1111-1111-111111111111",
                         "secret":"BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
                         "passphrase":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}
                        """)));

        ApiCredentials created = api().createBuilderApiKey(CALLER, CREDS, 1L).join();
        assertThat(created.apiKey()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(created.secret()).startsWith("BBBB");
        assertThat(created.passphrase()).startsWith("bbbb");
    }

    /**
     * Polymarket 后端实际返回的字段名是 {@code key}（rs-clob-client 的
     * {@code Credentials} 也是把 {@code key} 作为正字段、{@code apiKey} 作为
     * {@code #[serde(alias)]}）。这里固化「Java 同时接受 {@code key} / {@code apiKey}」
     * 的行为，避免下次又被旧 fixture 误导。
     */
    @Test
    void createBuilderApiKeyAcceptsKeyAlias() {
        wm.stubFor(post(urlPathEqualTo("/auth/builder-api-key"))
                .willReturn(okJson("""
                        {"key":"33333333-3333-3333-3333-333333333333",
                         "secret":"CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=",
                         "passphrase":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}
                        """)));

        ApiCredentials created = api().createBuilderApiKey(CALLER, CREDS, 1L).join();
        assertThat(created.apiKey()).isEqualTo("33333333-3333-3333-3333-333333333333");
        assertThat(created.secret()).startsWith("CCCC");
        assertThat(created.passphrase()).startsWith("cccc");
    }

    @Test
    void listBuilderApiKeysMergesBuilderHeadersAndParsesList() {
        wm.stubFor(get(urlPathEqualTo("/auth/builder-api-key"))
                .withHeader(L2HeaderBuilder.POLY_SIGNATURE, matching(".+"))
                .withHeader("POLY_BUILDER_API_KEY", equalTo("builder-key"))
                .withHeader("POLY_BUILDER_SIGNATURE", equalTo("builder-sig"))
                .willReturn(okJson("""
                        [{"key":"11111111-1111-1111-1111-111111111111",
                          "created_at":"2024-01-01T00:00:00Z","revoked_at":null},
                         {"key":"22222222-2222-2222-2222-222222222222",
                          "created_at":"2024-02-01T00:00:00Z","revoked_at":"2024-03-01T00:00:00Z"}]
                        """)));

        List<BuilderApiKeyResponse> list = api()
                .builderApiKeys(CALLER, CREDS, 1L, BUILDER_HEADERS)
                .join();

        assertThat(list).hasSize(2);
        assertThat(list.get(0).getKey()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(list.get(0).isActive()).isTrue();
        assertThat(list.get(1).isActive()).isFalse();
    }

    @Test
    void revokeBuilderApiKeyMergesBuilderHeaders() {
        wm.stubFor(delete(urlPathEqualTo("/auth/builder-api-key"))
                .withHeader(L2HeaderBuilder.POLY_SIGNATURE, matching(".+"))
                .withHeader("POLY_BUILDER_API_KEY", equalTo("builder-key"))
                .withHeader("POLY_BUILDER_SIGNATURE", equalTo("builder-sig"))
                // 空 body；用 {} 应答模拟真实服务端返回 JSON null
                .willReturn(okJson("{}")));

        api().revokeBuilderApiKey(CALLER, CREDS, 1L, BUILDER_HEADERS).join();
        // 走到这里即证明请求通过了 WireMock stub 校验
    }
}
