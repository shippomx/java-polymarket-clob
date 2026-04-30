package com.polymarket.clob.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;

/**
 * {@code GET /balance-allowance} 的响应体。
 *
 * <p>{@code balance} 用 {@link BigDecimal} 承接以保留 USDC 小数位精度；
 * {@code allowances} 是 {@code tokenAddress -> allowance 数值字符串} 的映射，
 * 若服务端缺省或为 {@code null}，构造时归一化为空 Map。</p>
 */
public final class BalanceAllowanceResponse {

    private final BigDecimal balance;
    private final Map<Address, String> allowances;

    @JsonCreator
    public BalanceAllowanceResponse(
            @JsonProperty("balance") BigDecimal balance,
            @JsonProperty("allowances") Map<Address, String> allowances) {
        this.balance = balance == null ? BigDecimal.ZERO : balance;
        this.allowances = allowances == null ? Map.of() : Collections.unmodifiableMap(allowances);
    }

    public BigDecimal balance() {
        return balance;
    }

    public Map<Address, String> allowances() {
        return allowances;
    }

    @Override
    public String toString() {
        return "BalanceAllowanceResponse{balance=" + balance + ", allowances=" + allowances + "}";
    }
}
