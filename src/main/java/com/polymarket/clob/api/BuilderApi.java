package com.polymarket.clob.api;

import com.polymarket.clob.api.model.BuilderApiKeyResponse;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.model.Address;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Builder 专用 API（{@code auth/builder-api-key} 及 Builder 专属读取端点的辅助入口）。
 *
 * <ul>
 *   <li>{@link #createBuilderApiKey}: {@code POST /auth/builder-api-key}。
 *       仅需要 L2 认证（不需要 Builder 头），因此任何已认证的用户都可调用；
 *       成功时返回一组新的 {@link ApiCredentials}，由调用方自行保存作为后续
 *       {@link com.polymarket.clob.auth.BuilderConfig.Local} 的 secret 来源。</li>
 *   <li>{@link #builderApiKeys}: {@code GET /auth/builder-api-key}。
 *       L2 + Builder 头，列出当前 Builder 账户已注册的 Key 列表。</li>
 *   <li>{@link #revokeBuilderApiKey}: {@code DELETE /auth/builder-api-key}。
 *       L2 + Builder 头，撤销当前 Builder Key。</li>
 * </ul>
 */
public interface BuilderApi {

    /** L2-only：创建新的 Builder API Key，返回其凭证。 */
    CompletableFuture<ApiCredentials> createBuilderApiKey(
            Address caller,
            ApiCredentials credentials,
            long timestamp);

    /**
     * L2 + Builder：列出 Builder Key。调用方需在 {@code builderHeaders} 传入
     * 由 {@link com.polymarket.clob.auth.BuilderHeaderBuilder} 生成的扩展头。
     */
    CompletableFuture<List<BuilderApiKeyResponse>> builderApiKeys(
            Address caller,
            ApiCredentials credentials,
            long timestamp,
            Map<String, String> builderHeaders);

    /** L2 + Builder：撤销当前 Builder Key。 */
    CompletableFuture<Void> revokeBuilderApiKey(
            Address caller,
            ApiCredentials credentials,
            long timestamp,
            Map<String, String> builderHeaders);
}
