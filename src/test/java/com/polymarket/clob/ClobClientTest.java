package com.polymarket.clob;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.polymarket.clob.model.ChainId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClobClientTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    @Test
    void builderRequiresEndpoint() {
        assertThatThrownBy(() -> ClobClient.builder().chainId(ChainId.POLYGON).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("endpoint");
    }

    @Test
    void builderRequiresChainId() {
        assertThatThrownBy(() -> ClobClient.builder()
                .endpoint("https://example.com").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chainId");
    }

    @Test
    void builderBuildsAndQueriesOk() {
        wm.stubFor(get(urlEqualTo("/")).willReturn(okJson("\"OK\"")));

        try (ClobClient client = ClobClient.builder()
                .endpoint(wm.baseUrl())
                .chainId(ChainId.POLYGON)
                .requestTimeout(Duration.ofSeconds(3))
                .build()) {
            assertThat(client.market().ok().join()).isEqualTo("OK");
        }
    }

    @Test
    void defaultEndpointResolvesToPolymarket() {
        try (ClobClient c = ClobClient.builder()
                .chainId(ChainId.POLYGON)
                .useDefaultEndpoint()
                .build()) {
            assertThat(c.endpoint().toString()).contains("clob.polymarket.com");
        }
    }

    @Test
    void endpointUriOverload() {
        try (ClobClient c = ClobClient.builder()
                .endpoint(URI.create("https://example.com"))
                .chainId(ChainId.AMOY)
                .build()) {
            assertThat(c.endpoint()).isEqualTo(URI.create("https://example.com"));
            assertThat(c.chainId()).isEqualTo(ChainId.AMOY);
        }
    }

    @Test
    void customHttpClientIsUsed() {
        wm.stubFor(get(urlEqualTo("/")).willReturn(okJson("\"OK\"")));

        HttpClient custom = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        try (ClobClient c = ClobClient.builder()
                .endpoint(wm.baseUrl())
                .chainId(ChainId.POLYGON)
                .httpClient(custom)
                .build()) {
            assertThat(c.market().ok().join()).isEqualTo("OK");
        }
    }

    @Test
    void negativeTimeoutRejected() {
        assertThatThrownBy(() -> ClobClient.builder()
                .requestTimeout(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClobClient.builder()
                .requestTimeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsRelativeEndpoint() {
        // M4: 相对 URI 在 build() 阶段就应立刻失败，而不是等到第一次请求
        assertThatThrownBy(() -> ClobClient.builder()
                .endpoint("/relative/path")
                .chainId(ChainId.POLYGON)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute");
    }

    @Test
    void rejectsNonHttpScheme() {
        // M4: ftp/file/ws 等 scheme 在当前阶段都不被支持，应快速失败
        assertThatThrownBy(() -> ClobClient.builder()
                .endpoint("ftp://example.com")
                .chainId(ChainId.POLYGON)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme");
        assertThatThrownBy(() -> ClobClient.builder()
                .endpoint("ws://example.com")
                .chainId(ChainId.POLYGON)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme");
    }

    @Test
    void rejectsEndpointWithoutHost() {
        // M4: 只有 scheme 没有 host 的 URI 也无法发请求，提前失败
        assertThatThrownBy(() -> ClobClient.builder()
                .endpoint("http:///nohost")
                .chainId(ChainId.POLYGON)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
    }

    @Test
    void nullHttpClientMeansDefault() {
        // M4: Javadoc 承诺 httpClient(null) 等同于不调用；应能正常 build
        try (ClobClient c = ClobClient.builder()
                .endpoint("https://example.com")
                .chainId(ChainId.POLYGON)
                .httpClient(null)
                .build()) {
            assertThat(c.endpoint().toString()).isEqualTo("https://example.com");
        }
    }

    @Test
    void closeIsIdempotent() {
        ClobClient c = ClobClient.builder()
                .endpoint("https://example.com")
                .chainId(ChainId.POLYGON)
                .build();
        c.close();
        c.close();
    }
}
