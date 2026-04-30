package com.polymarket.clob.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class HttpTransportCaptorTest {

    private WireMockServer wireMock;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void postCapturesMethodUriHeadersAndBodyBeforeSend() {
        wireMock.stubFor(post(urlEqualTo("/orders"))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

        List<Captured> captured = new ArrayList<>();
        RequestCaptor cap = (m, u, h, b) -> captured.add(new Captured(m, u, h, b));

        HttpTransport transport = HttpTransport.builder()
                .baseUri(URI.create(wireMock.baseUrl()))
                .httpClient(HttpClient.newHttpClient())
                .objectMapper(JsonCodec.objectMapper())
                .requestTimeout(Duration.ofSeconds(2))
                .requestCaptor(cap)
                .build();

        transport.post("orders", Map.of("k", "v"),
                Map.of("X-Custom", "h1"),
                new TypeReference<Map<String, Object>>() {}).join();

        assertThat(captured).hasSize(1);
        Captured c = captured.get(0);
        assertThat(c.method).isEqualTo("POST");
        assertThat(c.uri.getPath()).isEqualTo("/orders");
        assertThat(c.headers.get("X-Custom")).containsExactly("h1");
        assertThat(c.headers.get("Content-Type")).containsExactly("application/json");
        assertThat(new String(c.body)).isEqualTo("{\"k\":\"v\"}");
    }

    private record Captured(String method, URI uri,
                            Map<String, List<String>> headers, byte[] body) {}
}
