package com.polymarket.clob.order;

import java.util.Objects;

/**
 * 下单选项（对应 py-clob-client 的 {@code CreateOrderOptions}）。
 *
 * <p>必填：
 * <ul>
 *   <li>{@link #tickSize()}：市场最小价格变动单位（来自 {@code GET /tick-size} 或本地已知）。</li>
 *   <li>{@link #negRisk()}：多结果市场标志，决定 EIP-712 {@code verifyingContract} 是
 *       CTFExchange 还是 NegRiskCTFExchange。</li>
 * </ul>
 * </p>
 */
public record CreateOrderOptions(TickSize tickSize, boolean negRisk) {
    public CreateOrderOptions {
        Objects.requireNonNull(tickSize, "tickSize");
    }

    public static CreateOrderOptions of(TickSize tickSize, boolean negRisk) {
        return new CreateOrderOptions(tickSize, negRisk);
    }
}
