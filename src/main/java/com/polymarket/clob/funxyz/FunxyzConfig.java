package com.polymarket.clob.funxyz;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * {@link FunxyzClient} 的不可变配置。
 *
 * <p>{@code apiKey} 默认 {@code null}。{@code null} 表示 "未显式设置"，由
 * {@link FunxyzClient} 在构造时从 {@link #DEFAULT_FLAGS_CONFIG_URL} 拉取并解析。
 * CDN 不可达时回退到 {@link #DEFAULT_PUBLIC_API_KEY}。如果调用方显式调用
 * {@link Builder#apiKey(String)}，则跳过 CDN 直接使用该值。</p>
 */
public record FunxyzConfig(
        URI baseUrl,
        String apiKey,
        HttpClient httpClient,
        Duration requestTimeout,
        URI flagsConfigUrl,
        Duration flagsRequestTimeout
) {

    /** 默认 fun.xyz API 入口。 */
    public static final URI DEFAULT_BASE_URL = URI.create("https://api.fun.xyz");

    /** 默认 fun.xyz 前端特性开关 CDN 端点。 */
    public static final URI DEFAULT_FLAGS_CONFIG_URL =
            URI.create("https://sdk-cdn.fun.xyz/flags/v0/config.json");

    /**
     * CDN 拉取失败时的兜底 apiKey。
     * 来源：polymarket.com 前端硬编码字面量（HAR 反向工程获得，非用户私密）。
     * 如失效，重新抓包替换或调用方传入自己的 key。
     */
    public static final String DEFAULT_PUBLIC_API_KEY = "Y53dikxXdT4E3afI1l8BMBSWgyhKvf65k6Dut1k6";

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private URI baseUrl = DEFAULT_BASE_URL;
        private String apiKey = null;
        private HttpClient httpClient;
        private Duration requestTimeout = Duration.ofSeconds(10);
        private URI flagsConfigUrl = DEFAULT_FLAGS_CONFIG_URL;
        private Duration flagsRequestTimeout = Duration.ofSeconds(4);

        public Builder baseUrl(URI v) { this.baseUrl = v; return this; }
        public Builder apiKey(String v) { this.apiKey = v; return this; }
        public Builder httpClient(HttpClient v) { this.httpClient = v; return this; }
        public Builder requestTimeout(Duration v) { this.requestTimeout = v; return this; }
        public Builder flagsConfigUrl(URI v) { this.flagsConfigUrl = v; return this; }
        public Builder flagsRequestTimeout(Duration v) { this.flagsRequestTimeout = v; return this; }

        public FunxyzConfig build() {
            HttpClient hc = httpClient != null ? httpClient : HttpClient.newHttpClient();
            return new FunxyzConfig(baseUrl, apiKey, hc, requestTimeout,
                                    flagsConfigUrl, flagsRequestTimeout);
        }
    }
}
