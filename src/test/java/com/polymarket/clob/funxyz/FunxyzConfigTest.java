package com.polymarket.clob.funxyz;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class FunxyzConfigTest {

    @Test
    void buildExposesAllDefaults() {
        FunxyzConfig cfg = FunxyzConfig.builder().build();

        assertThat(cfg.baseUrl()).isEqualTo(URI.create("https://api.fun.xyz"));
        assertThat(cfg.apiKey()).isEqualTo("Y53dikxXdT4E3afI1l8BMBSWgyhKvf65k6Dut1k6");
        assertThat(cfg.httpClient()).isNotNull();
        assertThat(cfg.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void overridesAreHonored() {
        URI customUrl = URI.create("http://localhost:18080");
        HttpClient customHttp = HttpClient.newHttpClient();
        Duration customTimeout = Duration.ofSeconds(3);

        FunxyzConfig cfg = FunxyzConfig.builder()
                .baseUrl(customUrl)
                .apiKey("my-key")
                .httpClient(customHttp)
                .requestTimeout(customTimeout)
                .build();

        assertThat(cfg.baseUrl()).isSameAs(customUrl);
        assertThat(cfg.apiKey()).isEqualTo("my-key");
        assertThat(cfg.httpClient()).isSameAs(customHttp);
        assertThat(cfg.requestTimeout()).isEqualTo(customTimeout);
    }

    @Test
    void publicConstantsExposed() {
        assertThat(FunxyzConfig.DEFAULT_PUBLIC_API_KEY)
                .isEqualTo("Y53dikxXdT4E3afI1l8BMBSWgyhKvf65k6Dut1k6");
        assertThat(FunxyzConfig.DEFAULT_BASE_URL)
                .isEqualTo(URI.create("https://api.fun.xyz"));
    }
}
