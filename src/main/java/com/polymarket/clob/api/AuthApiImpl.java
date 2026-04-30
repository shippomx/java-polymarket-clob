package com.polymarket.clob.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.auth.L1HeaderBuilder;
import com.polymarket.clob.auth.L2HeaderBuilder;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.exception.ClobApiException;
import com.polymarket.clob.http.HttpTransport;
import com.polymarket.clob.model.ApiKeysResponse;
import com.polymarket.clob.model.Address;

import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * {@link AuthApi} 的默认实现。
 *
 * <p>所有方法只做 "签名 + 调 transport" 两件事；具体的 HMAC 与 EIP-712 逻辑都下沉在
 * {@link L1HeaderBuilder} / {@link L2HeaderBuilder}。</p>
 */
public final class AuthApiImpl implements AuthApi {

    private static final String AUTH_API_KEY_PATH = "/auth/api-key";
    private static final String AUTH_DERIVE_PATH = "/auth/derive-api-key";
    private static final String AUTH_API_KEYS_PATH = "/auth/api-keys";

    private final HttpTransport transport;
    private final long chainId;

    /**
     * @param transport 已配置的 REST transport
     * @param chainId   {@link Signer} 签名时使用的链 ID（首版仅用于 API Key 管理；apiKeys/delete 调用
     *                  不依赖 chainId，纯 L2）
     */
    public AuthApiImpl(HttpTransport transport, long chainId) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.chainId = chainId;
    }

    @Override
    public CompletableFuture<ApiCredentials> createApiKey(
            Signer signer, long chainId, long timestamp, BigInteger nonce) {
        return L1HeaderBuilder.build(signer, chainId, timestamp, nonce)
                .thenCompose(headers -> transport.post(
                        "auth/api-key", Map.of(), headers,
                        new TypeReference<ApiCredentials>() {}));
    }

    @Override
    public CompletableFuture<ApiCredentials> deriveApiKey(
            Signer signer, long chainId, long timestamp, BigInteger nonce) {
        return L1HeaderBuilder.build(signer, chainId, timestamp, nonce)
                .thenCompose(headers -> transport.get(
                        "auth/derive-api-key", Map.of(), headers,
                        new TypeReference<ApiCredentials>() {}));
    }

    @Override
    public CompletableFuture<ApiCredentials> createOrDeriveApiKey(
            Signer signer, long chainId, long timestamp, BigInteger nonce) {
        return createApiKey(signer, chainId, timestamp, nonce)
                .handle((creds, err) -> {
                    if (err == null) {
                        return CompletableFuture.completedFuture(creds);
                    }
                    Throwable cause = unwrap(err);
                    if (cause instanceof ClobApiException api
                            && api.getStatusCode() >= 400 && api.getStatusCode() < 500) {
                        return deriveApiKey(signer, chainId, timestamp, nonce);
                    }
                    CompletableFuture<ApiCredentials> fail = new CompletableFuture<>();
                    fail.completeExceptionally(cause);
                    return fail;
                })
                .thenCompose(f -> f);
    }

    @Override
    public CompletableFuture<ApiKeysResponse> apiKeys(
            Address caller, ApiCredentials credentials, long timestamp) {
        Map<String, String> headers = L2HeaderBuilder.build(
                caller, credentials, "GET", AUTH_API_KEYS_PATH, "", timestamp);
        return transport.get("auth/api-keys", Map.of(), headers,
                new TypeReference<ApiKeysResponse>() {});
    }

    @Override
    public CompletableFuture<JsonNode> deleteApiKey(
            Address caller, ApiCredentials credentials, long timestamp) {
        Map<String, String> headers = L2HeaderBuilder.build(
                caller, credentials, "DELETE", AUTH_API_KEY_PATH, "", timestamp);
        return transport.delete("auth/api-key", Map.of(), headers,
                new TypeReference<JsonNode>() {});
    }

    /** 去掉 CompletionException / ExecutionException 外壳，拿到最内层原始异常。 */
    private static Throwable unwrap(Throwable t) {
        Throwable cause = t;
        while (cause instanceof java.util.concurrent.CompletionException
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /** 默认 chainId（用于 {@code AuthenticatedClobClient.authenticate()} 的兜底签名）。 */
    public long chainId() {
        return chainId;
    }
}
