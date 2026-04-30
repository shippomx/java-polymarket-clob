package com.polymarket.clob.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * {@code GET /auth/api-keys} 的响应体，列出当前地址已注册的所有 API Key UUID。
 *
 * <p>服务端字段命名为 {@code apiKeys}；若账号无 Key，字段可能缺省或为 {@code null}，
 * 本类构造时归一化为空列表，调用侧无需再判空。</p>
 */
public final class ApiKeysResponse {

    private final List<String> apiKeys;

    @JsonCreator
    public ApiKeysResponse(@JsonProperty("apiKeys") List<String> apiKeys) {
        this.apiKeys = apiKeys == null ? List.of() : List.copyOf(apiKeys);
    }

    public List<String> apiKeys() {
        return apiKeys;
    }

    @Override
    public String toString() {
        return "ApiKeysResponse" + apiKeys;
    }
}
