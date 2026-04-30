package com.polymarket.clob.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class CursorPagerTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    record Item(String id) {}

    private HttpTransport transport() {
        return new HttpTransport(
                URI.create(wm.baseUrl() + "/"),
                SharedHttpClient.INSTANCE,
                JsonCodec.objectMapper(),
                Duration.ofSeconds(5));
    }

    @Test
    void streamsAcrossPagesUntilTerminalCursor() {
        wm.stubFor(get(urlPathEqualTo("/items"))
                .withQueryParam("next_cursor", absent())
                .willReturn(okJson("{\"data\":[{\"id\":\"a\"},{\"id\":\"b\"}],\"next_cursor\":\"page2\"}")));
        wm.stubFor(get(urlPathEqualTo("/items"))
                .withQueryParam("next_cursor", equalTo("page2"))
                .willReturn(okJson("{\"data\":[{\"id\":\"c\"}],\"next_cursor\":\"LTE=\"}")));

        List<String> ids = CursorPager
                .stream(transport(), "items", Map.of(), new TypeReference<CursorPager.Page<Item>>() {})
                .map(Item::id)
                .collect(Collectors.toList());

        assertThat(ids).containsExactly("a", "b", "c");
    }

    @Test
    void singlePageWithTerminalCursorTerminates() {
        wm.stubFor(get(urlPathEqualTo("/items"))
                .willReturn(okJson("{\"data\":[{\"id\":\"x\"}],\"next_cursor\":\"LTE=\"}")));

        List<String> ids = CursorPager
                .stream(transport(), "items", Map.of(), new TypeReference<CursorPager.Page<Item>>() {})
                .map(Item::id)
                .collect(Collectors.toList());

        assertThat(ids).containsExactly("x");
    }

    @Test
    void emptyFirstPageWithTerminalCursor() {
        wm.stubFor(get(urlPathEqualTo("/items"))
                .willReturn(okJson("{\"data\":[],\"next_cursor\":\"LTE=\"}")));

        long count = CursorPager
                .stream(transport(), "items", Map.of(), new TypeReference<CursorPager.Page<Item>>() {})
                .count();

        assertThat(count).isEqualTo(0);
    }

    @Test
    void nullNextCursorTerminates() {
        // 服务端不返回 next_cursor 字段（null）也应当终止
        wm.stubFor(get(urlPathEqualTo("/items"))
                .willReturn(okJson("{\"data\":[{\"id\":\"y\"}]}")));

        List<String> ids = CursorPager
                .stream(transport(), "items", Map.of(), new TypeReference<CursorPager.Page<Item>>() {})
                .map(Item::id)
                .collect(Collectors.toList());

        assertThat(ids).containsExactly("y");
    }

    @Test
    void baseParamsPropagatedToEachPage() {
        wm.stubFor(get(urlPathEqualTo("/items"))
                .withQueryParam("market", equalTo("m1"))
                .withQueryParam("next_cursor", absent())
                .willReturn(okJson("{\"data\":[{\"id\":\"a\"}],\"next_cursor\":\"p2\"}")));
        wm.stubFor(get(urlPathEqualTo("/items"))
                .withQueryParam("market", equalTo("m1"))
                .withQueryParam("next_cursor", equalTo("p2"))
                .willReturn(okJson("{\"data\":[{\"id\":\"b\"}],\"next_cursor\":\"LTE=\"}")));

        List<String> ids = CursorPager
                .stream(transport(), "items", Map.of("market", "m1"),
                        new TypeReference<CursorPager.Page<Item>>() {})
                .map(Item::id)
                .collect(Collectors.toList());

        assertThat(ids).containsExactly("a", "b");
    }

    @Test
    void streamIsLazy() {
        // 如果 stream 非惰性，limit(1) 仍会请求 page2；所以只 stub 首页即可
        wm.stubFor(get(urlPathEqualTo("/items"))
                .withQueryParam("next_cursor", absent())
                .willReturn(okJson("{\"data\":[{\"id\":\"a\"},{\"id\":\"b\"}],\"next_cursor\":\"page2\"}")));

        Item first = CursorPager
                .stream(transport(), "items", Map.of(),
                        new TypeReference<CursorPager.Page<Item>>() {})
                .findFirst()
                .orElseThrow();

        assertThat(first.id()).isEqualTo("a");
        // 不校验未发生的 page2 请求：WireMock 未 stub 即拒绝
    }
}
