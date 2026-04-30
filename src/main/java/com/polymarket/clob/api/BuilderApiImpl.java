package com.polymarket.clob.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.polymarket.clob.api.model.BuilderApiKeyResponse;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.auth.L2HeaderBuilder;
import com.polymarket.clob.http.HttpTransport;
import com.polymarket.clob.model.Address;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * {@link BuilderApi} 的默认实现。与 {@link AuthApiImpl} 互补：
 * 前者管 Builder-specific 的 key 生命周期，后者管普通 L1/L2 key。
 */
public final class BuilderApiImpl implements BuilderApi {

    private static final String BUILDER_API_KEY_PATH = "/auth/builder-api-key";

    private final HttpTransport transport;

    public BuilderApiImpl(HttpTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public CompletableFuture<ApiCredentials> createBuilderApiKey(Address caller,
                                                                 ApiCredentials credentials,
                                                                 long timestamp) {
        // POST 请求无 body，L2 HMAC 中 body 段为空字符串
        Map<String, String> headers = L2HeaderBuilder.build(
                caller, credentials, "POST", BUILDER_API_KEY_PATH, "", timestamp);
        return transport.postRaw("auth/builder-api-key", "", headers,
                new TypeReference<ApiCredentials>() {});
    }

    @Override
    public CompletableFuture<List<BuilderApiKeyResponse>> builderApiKeys(Address caller,
                                                                         ApiCredentials credentials,
                                                                         long timestamp,
                                                                         Map<String, String> builderHeaders) {
        Map<String, String> headers = mergeWithBuilder(
                L2HeaderBuilder.build(caller, credentials, "GET", BUILDER_API_KEY_PATH, "", timestamp),
                builderHeaders);
        return transport.get("auth/builder-api-key", Map.of(), headers,
                new TypeReference<List<BuilderApiKeyResponse>>() {});
    }

    @Override
    public CompletableFuture<Void> revokeBuilderApiKey(Address caller,
                                                       ApiCredentials credentials,
                                                       long timestamp,
                                                       Map<String, String> builderHeaders) {
        Map<String, String> headers = mergeWithBuilder(
                L2HeaderBuilder.build(caller, credentials, "DELETE", BUILDER_API_KEY_PATH, "", timestamp),
                builderHeaders);
        // 服务端 204 时 body 为空，用宽松的 JsonNode 承接；无论返回啥，只要状态码正常就视作成功
        return transport.delete("auth/builder-api-key", Map.of(), headers,
                        new TypeReference<JsonNode>() {})
                .thenApply(r -> null);
    }

    private static Map<String, String> mergeWithBuilder(Map<String, String> base,
                                                         Map<String, String> extra) {
        if (extra == null || extra.isEmpty()) return base;
        Map<String, String> merged = new LinkedHashMap<>(base);
        merged.putAll(extra);
        return merged;
    }
}
