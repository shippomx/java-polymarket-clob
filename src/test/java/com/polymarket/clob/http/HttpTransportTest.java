package com.polymarket.clob.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.http.Fault;
import com.polymarket.clob.exception.ClobApiException;
import com.polymarket.clob.exception.ClobSerializationException;
import com.polymarket.clob.exception.ClobTransportException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpTransportTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .failOnUnmatchedRequests(true)
            .build();

    record Midpoint(BigDecimal mid) {}

    private HttpTransport transport() {
        return new HttpTransport(
                URI.create(wm.baseUrl() + "/"),
                SharedHttpClient.INSTANCE,
                JsonCodec.objectMapper(),
                Duration.ofSeconds(5));
    }

    @Test
    void getParsesJsonBody() {
        wm.stubFor(get(urlPathEqualTo("/midpoint"))
                .withQueryParam("token_id", equalTo("42"))
                .willReturn(okJson("{\"mid\":\"0.55\"}")));

        Midpoint mid = transport()
                .get("midpoint", Map.of("token_id", "42"), Map.of(),
                        new TypeReference<Midpoint>() {})
                .join();

        assertThat(mid.mid()).isEqualByComparingTo("0.55");
    }

    @Test
    void getWithoutQueryParams() {
        wm.stubFor(get(urlPathEqualTo("/midpoint"))
                .willReturn(okJson("{\"mid\":\"1\"}")));
        Midpoint mid = transport()
                .get("midpoint", Map.of(), Map.of(), new TypeReference<Midpoint>() {})
                .join();
        assertThat(mid.mid()).isEqualByComparingTo("1");
    }

    @Test
    void leadingSlashPathNormalized() {
        wm.stubFor(get(urlPathEqualTo("/midpoint"))
                .willReturn(okJson("{\"mid\":\"0.5\"}")));
        Midpoint mid = transport()
                .get("/midpoint", Map.of(), Map.of(), new TypeReference<Midpoint>() {})
                .join();
        assertThat(mid.mid()).isEqualByComparingTo("0.5");
    }

    @Test
    void customHeaderPropagated() {
        wm.stubFor(get(urlPathEqualTo("/ping"))
                .withHeader("X-Trace-Id", equalTo("abc"))
                .willReturn(okJson("{\"mid\":\"0\"}")));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Trace-Id", "abc");
        Midpoint mid = transport()
                .get("ping", Map.of(), headers, new TypeReference<Midpoint>() {})
                .join();
        assertThat(mid.mid()).isEqualByComparingTo("0");
    }

    @Test
    void iterableQueryExpandsToRepeatedKey() {
        wm.stubFor(get(urlPathEqualTo("/lookup"))
                .withQueryParam("id", havingExactly("a", "b"))
                .willReturn(okJson("{\"mid\":\"0.5\"}")));
        Midpoint mid = transport()
                .get("lookup", Map.of("id", List.of("a", "b")), Map.of(),
                        new TypeReference<Midpoint>() {})
                .join();
        assertThat(mid.mid()).isEqualByComparingTo("0.5");
    }

    @Test
    void non2xxThrowsClobApiException() {
        wm.stubFor(get(urlPathEqualTo("/midpoint"))
                .willReturn(aResponse().withStatus(404).withBody("not found")));

        assertThatThrownBy(() -> transport()
                .get("midpoint", Map.of("token_id", "42"), Map.of(),
                        new TypeReference<Midpoint>() {})
                .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ClobApiException.class)
                .satisfies(t -> {
                    ClobApiException cause = (ClobApiException) t.getCause();
                    assertThat(cause.getStatusCode()).isEqualTo(404);
                    assertThat(cause.getMethod()).isEqualTo("GET");
                    assertThat(cause.getPath()).isEqualTo("/midpoint");
                    assertThat(cause.getBody()).isEqualTo("not found");
                });
    }

    @Test
    void serverErrorMapsToClobApi5xx() {
        wm.stubFor(get(urlPathEqualTo("/boom"))
                .willReturn(aResponse().withStatus(500).withBody("oops")));

        assertThatThrownBy(() -> transport()
                .get("boom", Map.of(), Map.of(), new TypeReference<Midpoint>() {})
                .join())
                .hasCauseInstanceOf(ClobApiException.class)
                .satisfies(t -> {
                    ClobApiException cause = (ClobApiException) t.getCause();
                    assertThat(cause.getStatusCode()).isEqualTo(500);
                });
    }

    @Test
    void malformedJsonResponseWrappedAsSerializationException() {
        wm.stubFor(get(urlPathEqualTo("/midpoint"))
                .willReturn(okJson("not json at all")));

        assertThatThrownBy(() -> transport()
                .get("midpoint", Map.of(), Map.of(), new TypeReference<Midpoint>() {})
                .join())
                .hasCauseInstanceOf(ClobSerializationException.class);
    }

    @Test
    void postSerializesBody() {
        wm.stubFor(post(urlPathEqualTo("/midpoints"))
                .withRequestBody(equalToJson("[{\"token_id\":\"42\"}]"))
                .willReturn(okJson("{\"mid\":\"0.5\"}")));

        Midpoint result = transport()
                .post("midpoints",
                        List.of(Map.of("token_id", "42")),
                        Map.of(),
                        new TypeReference<Midpoint>() {})
                .join();

        assertThat(result.mid()).isEqualByComparingTo("0.5");
    }

    @Test
    void postSetsContentTypeJson() {
        wm.stubFor(post(urlPathEqualTo("/echo"))
                .withHeader("Content-Type", equalTo("application/json"))
                .willReturn(okJson("{\"mid\":\"0\"}")));
        Midpoint mid = transport()
                .post("echo", Map.of("x", 1), Map.of(), new TypeReference<Midpoint>() {})
                .join();
        assertThat(mid.mid()).isEqualByComparingTo("0");
    }

    record CancelRequest(String orderID) {}

    @Test
    void deleteWithBodySerializesAndSetsContentType() {
        wm.stubFor(delete(urlPathEqualTo("/order"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("{\"orderID\":\"abc\"}"))
                .willReturn(okJson("{\"mid\":\"0.5\"}")));

        Midpoint result = transport()
                .deleteWithBody("order",
                        new CancelRequest("abc"),
                        Map.of(),
                        new TypeReference<Midpoint>() {})
                .join();

        assertThat(result.mid()).isEqualByComparingTo("0.5");
    }

    @Test
    void deleteWithNullBodySkipsContentType() {
        // cancel-all 场景：空 DELETE body，不应带 Content-Type
        wm.stubFor(delete(urlPathEqualTo("/cancel-all"))
                .withHeader("Content-Type", absent())
                .willReturn(okJson("{\"mid\":\"0\"}")));

        Midpoint result = transport()
                .deleteWithBody("cancel-all", null, Map.of(),
                        new TypeReference<Midpoint>() {})
                .join();

        assertThat(result.mid()).isEqualByComparingTo("0");
    }

    @Test
    void constructorRejectsNull() {
        assertThatThrownBy(() -> new HttpTransport(null,
                SharedHttpClient.INSTANCE, JsonCodec.objectMapper(), Duration.ofSeconds(1)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void connectionResetMapsToClobTransportException() {
        wm.stubFor(get(urlPathEqualTo("/rst"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> transport()
                .get("rst", Map.of(), Map.of(), new TypeReference<Midpoint>() {})
                .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ClobTransportException.class)
                .satisfies(t -> {
                    ClobTransportException cause = (ClobTransportException) t.getCause();
                    assertThat(cause.getCause()).isNotNull();
                    assertThat(cause.getMessage()).contains("GET").contains("/rst");
                });
    }

    @Test
    void requestTimeoutMapsToClobTransportException() {
        wm.stubFor(get(urlPathEqualTo("/slow"))
                .willReturn(aResponse().withFixedDelay(1_500).withStatus(200).withBody("{\"mid\":\"0\"}")));

        HttpTransport t = new HttpTransport(
                URI.create(wm.baseUrl() + "/"),
                SharedHttpClient.INSTANCE,
                JsonCodec.objectMapper(),
                Duration.ofMillis(200));

        assertThatThrownBy(() -> t
                .get("slow", Map.of(), Map.of(), new TypeReference<Midpoint>() {})
                .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ClobTransportException.class);
    }

    @Test
    void baseUriNormalizedToTrailingSlash() {
        // 传入不带尾斜杠的 baseUri，拼接仍应正确
        HttpTransport t = new HttpTransport(
                URI.create(wm.baseUrl()),
                SharedHttpClient.INSTANCE,
                JsonCodec.objectMapper(),
                Duration.ofSeconds(5));
        wm.stubFor(get(urlPathEqualTo("/foo"))
                .willReturn(okJson("{\"mid\":\"0\"}")));
        Midpoint mid = t.get("foo", Map.of(), Map.of(), new TypeReference<Midpoint>() {}).join();
        assertThat(mid.mid()).isEqualByComparingTo("0");
    }

    @Test
    void pathSegmentEncodesSpecialChars() {
        // M1: URL 保留字符 / 空格 / 非 ASCII 必须被 percent-encode
        assertThat(HttpTransport.pathSegment("abc")).isEqualTo("abc");
        assertThat(HttpTransport.pathSegment("a b")).isEqualTo("a%20b");
        assertThat(HttpTransport.pathSegment("a/b")).isEqualTo("a%2Fb");
        assertThat(HttpTransport.pathSegment("a?b#c")).isEqualTo("a%3Fb%23c");
        assertThat(HttpTransport.pathSegment("中")).isEqualTo("%E4%B8%AD");
    }

    @Test
    void pathSegmentUsedInUrl() {
        // M1: conditionId 含特殊字符时，实际请求 path 应是编码后的
        wm.stubFor(get(urlPathEqualTo("/markets/a%20b%2Fc"))
                .willReturn(okJson("{\"mid\":\"0\"}")));

        Midpoint mid = transport()
                .get("markets/" + HttpTransport.pathSegment("a b/c"),
                        Map.of(), Map.of(), new TypeReference<Midpoint>() {})
                .join();
        assertThat(mid.mid()).isEqualByComparingTo("0");
    }

    @Test
    void emptyAndSlashPathResolveIdentically() {
        // M6: "" 与 "/" 都应打到 baseUri 根
        wm.stubFor(get(urlEqualTo("/"))
                .willReturn(okJson("{\"mid\":\"0\"}")));
        Midpoint a = transport()
                .get("", Map.of(), Map.of(), new TypeReference<Midpoint>() {}).join();
        Midpoint b = transport()
                .get("/", Map.of(), Map.of(), new TypeReference<Midpoint>() {}).join();
        assertThat(a.mid()).isEqualByComparingTo("0");
        assertThat(b.mid()).isEqualByComparingTo("0");
    }

    @Test
    void defaultUserAgentAndAcceptHeadersInjected() {
        // M3: 默认 UA 匹配 polymarket-clob-java/*; Accept 固定 application/json
        wm.stubFor(get(urlPathEqualTo("/ping"))
                .withHeader("User-Agent", matching("polymarket-clob-java/.*"))
                .withHeader("Accept", equalTo("application/json"))
                .willReturn(okJson("{\"mid\":\"0\"}")));
        Midpoint mid = transport()
                .get("ping", Map.of(), Map.of(), new TypeReference<Midpoint>() {})
                .join();
        assertThat(mid.mid()).isEqualByComparingTo("0");
    }

    @Test
    void callerHeaderOverridesDefaults() {
        // M3: 调用方传入的同名 header 应覆盖默认 UA
        wm.stubFor(get(urlPathEqualTo("/ping"))
                .withHeader("User-Agent", equalTo("custom-ua/1.0"))
                .willReturn(okJson("{\"mid\":\"0\"}")));
        Midpoint mid = transport()
                .get("ping", Map.of(), Map.of("User-Agent", "custom-ua/1.0"),
                        new TypeReference<Midpoint>() {})
                .join();
        assertThat(mid.mid()).isEqualByComparingTo("0");
    }

    @Test
    void defaultUserAgentConstantFormat() {
        // 默认 UA 必须形如 polymarket-clob-java/xxx，便于服务端日志区分客户端
        assertThat(HttpTransport.DEFAULT_USER_AGENT).startsWith("polymarket-clob-java/");
        assertThat(HttpTransport.DEFAULT_ACCEPT).isEqualTo("application/json");
    }

    @Test
    void postRejectsNullBody() {
        // N8: 显式 requireNonNull，比静默发 "null" 字面量更安全
        assertThatThrownBy(() -> transport()
                .post("echo", null, Map.of(), new TypeReference<Midpoint>() {}))
                .isInstanceOf(NullPointerException.class);
    }
}
