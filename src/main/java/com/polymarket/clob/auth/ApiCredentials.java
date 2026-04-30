package com.polymarket.clob.auth;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * CLOB L2 API Key 三元组：{@code apiKey} / {@code secret} / {@code passphrase}。
 *
 * <p>反序列化：用于解析 {@code POST /auth/api-key}、{@code GET /auth/derive-api-key}、
 * {@code POST /auth/builder-api-key} 的响应。
 * 不同端点的 wire 字段名不一致——
 * 老接口（create/derive）返回 {@code apiKey}，新接口（builder-api-key）返回 {@code key}（与
 * {@code @polymarket/builder-relayer-client}、{@code rs-clob-client} 的 {@code Credentials}
 * 一致：正字段 {@code key}，{@code apiKey} 仅作为 alias）。
 * 因此这里同时接受 {@code apiKey} 与 {@code key}，与 Rust SDK 的 {@code #[serde(alias = "apiKey")]}
 * 行为一致。
 * {@code secret} 与 {@code passphrase} 使用
 * {@link com.fasterxml.jackson.annotation.JsonProperty.Access#WRITE_ONLY}，这样 Jackson
 * 在 SDK 自身 {@code writeValueAsString} 输出时不会再把密钥吐回去（仅 {@code apiKey} 可见）。</p>
 *
 * <p>{@link #toString()} 对所有敏感字段做定长掩码，保证日志里看不到明文。
 * 包内（{@code com.polymarket.clob.auth}）通过 {@link #secret()} / {@link #passphrase()}
 * 获取原始值仅供 HMAC 计算；不要再暴露给其它包。</p>
 */
public final class ApiCredentials {

    private final String apiKey;
    private final String secret;
    private final String passphrase;

    @JsonCreator
    public ApiCredentials(
            @JsonProperty("apiKey") @JsonAlias("key") String apiKey,
            @JsonProperty(value = "secret", access = JsonProperty.Access.WRITE_ONLY) String secret,
            @JsonProperty(value = "passphrase", access = JsonProperty.Access.WRITE_ONLY) String passphrase) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.secret = Objects.requireNonNull(secret, "secret");
        this.passphrase = Objects.requireNonNull(passphrase, "passphrase");
    }

    @JsonProperty("apiKey")
    public String apiKey() {
        return apiKey;
    }

    /**
     * 原始 secret（base64url 编码）。敏感字段，只应传入 HMAC 计算；
     * {@link #toString()} 已做掩码，Jackson {@code WRITE_ONLY} 已阻断序列化泄漏，
     * 因此可对其它模块（例如 Builder 子包）开放访问。
     */
    public String secret() {
        return secret;
    }

    /** 原始 passphrase，敏感；同 {@link #secret()} 的保护策略。 */
    public String passphrase() {
        return passphrase;
    }

    @Override
    public String toString() {
        return "ApiCredentials{apiKey=" + apiKey
                + ", secret=***, passphrase=***}";
    }

    @Override
    public boolean equals(Object o) {
        return this == o
                || (o instanceof ApiCredentials c
                && apiKey.equals(c.apiKey)
                && secret.equals(c.secret)
                && passphrase.equals(c.passphrase));
    }

    @Override
    public int hashCode() {
        return Objects.hash(apiKey, secret, passphrase);
    }
}
