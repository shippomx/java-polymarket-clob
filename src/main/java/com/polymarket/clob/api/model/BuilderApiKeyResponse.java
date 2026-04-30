package com.polymarket.clob.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;

/**
 * {@code GET /auth/builder-api-key} 返回的每个 Builder API Key 的元数据，
 * 对应 Rust {@code BuilderApiKeyResponse}：
 * <ul>
 *   <li>{@code key}：UUID，对应 {@code POLY_BUILDER_API_KEY} 头。</li>
 *   <li>{@code created_at} / {@code revoked_at}：ISO-8601 时间戳；后者非 null 表示已撤销。</li>
 * </ul>
 */
@Value
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
public class BuilderApiKeyResponse {
    @JsonProperty("key") String key;
    @JsonProperty("created_at") Instant createdAt;
    @JsonProperty("revoked_at") Instant revokedAt;

    /** 快捷判定：Key 是否仍然可用。 */
    public boolean isActive() {
        return revokedAt == null;
    }
}
