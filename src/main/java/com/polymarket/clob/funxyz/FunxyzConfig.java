package com.polymarket.clob.funxyz;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * {@link FunxyzClient} 的不可变配置。所有字段均带默认值。
 *
 * <p>{@code apiKey} 默认值是 Polymarket 前端硬编码的 fun.xyz public key（2026-05-07 抓包），
 * 适合开箱即用。如需替换成自有 fun.xyz 账号 key，用 {@link Builder#apiKey(String)} 覆盖。</p>
 */
public record FunxyzConfig(
        URI baseUrl,
        String apiKey,
        HttpClient httpClient,
        Duration requestTimeout
) {

    /** 默认 fun.xyz API 入口。 */
    public static final URI DEFAULT_BASE_URL = URI.create("https://api.fun.xyz");

    /**
     * Polymarket 前端公开 fun.xyz key（HAR 反向工程获得，非用户私密）。
     * 来源：{@code https://polymarket.com/_next/static/chunks/*.js} 中硬编码字面量。
     * 如失效，重新抓包替换或调用方传入自己的 key。
     */
    public static final String DEFAULT_PUBLIC_API_KEY = "Y53dikxXdT4E3afI1l8BMBSWgyhKvf65k6Dut1k6";

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private URI baseUrl = DEFAULT_BASE_URL;
        private String apiKey = DEFAULT_PUBLIC_API_KEY;
        private HttpClient httpClient;
        private Duration requestTimeout = Duration.ofSeconds(10);

        public Builder baseUrl(URI v) { this.baseUrl = v; return this; }
        public Builder apiKey(String v) { this.apiKey = v; return this; }
        public Builder httpClient(HttpClient v) { this.httpClient = v; return this; }
        public Builder requestTimeout(Duration v) { this.requestTimeout = v; return this; }

        public FunxyzConfig build() {
            HttpClient hc = httpClient != null ? httpClient : HttpClient.newHttpClient();
            return new FunxyzConfig(baseUrl, apiKey, hc, requestTimeout);
        }
    }
}
