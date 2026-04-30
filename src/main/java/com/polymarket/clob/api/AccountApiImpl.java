package com.polymarket.clob.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.auth.L2HeaderBuilder;
import com.polymarket.clob.http.HttpTransport;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.BalanceAllowanceRequest;
import com.polymarket.clob.model.BalanceAllowanceResponse;
import com.polymarket.clob.model.BanStatusResponse;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * {@link AccountApi} 的默认实现。
 *
 * <p>L2 签名时用 <em>不含 query</em> 的 path（{@code /balance-allowance}）——与 Rust
 * {@code reqwest::Url::path()} 的返回保持一致。</p>
 */
public final class AccountApiImpl implements AccountApi {

    private static final String BALANCE_ALLOWANCE_PATH = "/balance-allowance";
    private static final String CLOSED_ONLY_PATH = "/auth/ban-status/closed-only";

    private final HttpTransport transport;

    public AccountApiImpl(HttpTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public CompletableFuture<BalanceAllowanceResponse> balanceAllowance(
            Address caller,
            ApiCredentials credentials,
            long timestamp,
            BalanceAllowanceRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, String> headers = L2HeaderBuilder.build(
                caller, credentials, "GET", BALANCE_ALLOWANCE_PATH, "", timestamp);
        return transport.get("balance-allowance", request.toQueryParams(), headers,
                new TypeReference<BalanceAllowanceResponse>() {});
    }

    @Override
    public CompletableFuture<BanStatusResponse> closedOnlyMode(
            Address caller,
            ApiCredentials credentials,
            long timestamp) {
        Map<String, String> headers = L2HeaderBuilder.build(
                caller, credentials, "GET", CLOSED_ONLY_PATH, "", timestamp);
        return transport.get("auth/ban-status/closed-only", Map.of(), headers,
                new TypeReference<BanStatusResponse>() {});
    }
}
