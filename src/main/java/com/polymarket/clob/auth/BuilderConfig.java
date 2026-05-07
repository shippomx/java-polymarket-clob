package com.polymarket.clob.auth;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * Builder 认证配置，对齐 Rust {@code auth::builder::Config}：
 *
 * <ul>
 *   <li>{@link Local} — 本地持有一组 Builder API Key，内部直接用 HMAC 计算 signature。</li>
 *   <li>{@link Remote} — 不在本地持有 Builder 密钥，转发给可信的 signing 服务器，
 *       响应里直接带回 {@code POLY_BUILDER_*} 四个字段。</li>
 * </ul>
 *
 * <p>sealed 接口把两种形态封死；扩展新形态需要同时更新 {@link BuilderHeaderBuilder}
 * 的 switch，编译器会强制对齐。</p>
 */
public sealed interface BuilderConfig permits BuilderConfig.Local, BuilderConfig.Remote {

    /**
     * 本地 Builder 凭证。与 {@link ApiCredentials} 结构一致：
     * {@code apiKey / secret / passphrase} 三元组；secret 为 URL-safe base64。
     */
    record Local(ApiCredentials credentials) implements BuilderConfig {
        public Local {
            Objects.requireNonNull(credentials, "credentials");
        }
    }

    /**
     * 远程 signing 服务器。
     *
     * @param host  签名端点 URL，{@code https://host/.../sign} 级别；SDK 不会附加 path。
     * @param token 可选 bearer token，非空则作为 {@code Authorization: Bearer ...} 注入。
     */
    record Remote(URI host, Optional<String> token) implements BuilderConfig {
        public Remote {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(token, "token");
        }

        public static Remote of(String host, String token) {
            return new Remote(URI.create(Objects.requireNonNull(host, "host")),
                    Optional.ofNullable(token));
        }
    }

    /** 便利工厂：本地凭证。 */
    static BuilderConfig local(ApiCredentials credentials) {
        return new Local(credentials);
    }

    /** 便利工厂：远程 signing 服务器，可选 token。 */
    static BuilderConfig remote(String host, String token) {
        return Remote.of(host, token);
    }
}
