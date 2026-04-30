package com.polymarket.clob.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.model.ApiKeysResponse;
import com.polymarket.clob.model.Address;

import java.math.BigInteger;
import java.util.concurrent.CompletableFuture;

/**
 * CLOB API Key 管理接口。
 *
 * <p>前三个方法（create / derive / createOrDerive）用 L1 EIP-712 签名，面向尚未获取凭证的钱包；
 * 后两个（apiKeys / deleteApiKey）必须带上已有凭证做 L2 HMAC 鉴权，因此必须提供调用者地址与
 * {@link ApiCredentials}。</p>
 *
 * <p>{@code nonce} 与 {@code timestamp} 由调用方提供以便测试可重放；生产环境一般由
 * {@code AuthenticatedClobClient} 自动填入 {@code Instant.now().getEpochSecond()}。</p>
 */
public interface AuthApi {

    /** {@code POST /auth/api-key}：注册新的 API Key。 */
    CompletableFuture<ApiCredentials> createApiKey(
            Signer signer, long chainId, long timestamp, BigInteger nonce);

    /** {@code GET /auth/derive-api-key}：返回该 EOA 已存在的 API Key。 */
    CompletableFuture<ApiCredentials> deriveApiKey(
            Signer signer, long chainId, long timestamp, BigInteger nonce);

    /**
     * 幂等获取凭证：先尝试 {@link #createApiKey}，若上游返回 4xx（例如已存在）则回落到
     * {@link #deriveApiKey}；5xx 或网络错误保持向上抛出不做吞没。
     */
    CompletableFuture<ApiCredentials> createOrDeriveApiKey(
            Signer signer, long chainId, long timestamp, BigInteger nonce);

    /** {@code GET /auth/api-keys}：列出当前地址持有的全部 API Key UUID。 */
    CompletableFuture<ApiKeysResponse> apiKeys(
            Address caller, ApiCredentials credentials, long timestamp);

    /** {@code DELETE /auth/api-key}：删除当前使用的 API Key；返回服务端任意 JSON。 */
    CompletableFuture<JsonNode> deleteApiKey(
            Address caller, ApiCredentials credentials, long timestamp);
}
