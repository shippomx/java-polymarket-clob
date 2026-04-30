package com.polymarket.clob.api;

import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.BalanceAllowanceRequest;
import com.polymarket.clob.model.BalanceAllowanceResponse;
import com.polymarket.clob.model.BanStatusResponse;

import java.util.concurrent.CompletableFuture;

/**
 * 账户资金与 ban 状态查询接口。所有方法都需 L2 凭证。
 */
public interface AccountApi {

    /**
     * {@code GET /balance-allowance}：USDC 余额与对 Exchange 的 allowance。
     *
     * @param caller       调用者 EOA（签名 POLY_ADDRESS 头）
     * @param credentials  L2 凭证
     * @param timestamp    Unix 秒
     * @param request      资产类型 / token_id / signature_type 筛选项
     */
    CompletableFuture<BalanceAllowanceResponse> balanceAllowance(
            Address caller,
            ApiCredentials credentials,
            long timestamp,
            BalanceAllowanceRequest request);

    /** {@code GET /auth/ban-status/closed-only}：仅关仓模式的封禁状态。 */
    CompletableFuture<BanStatusResponse> closedOnlyMode(
            Address caller,
            ApiCredentials credentials,
            long timestamp);
}
