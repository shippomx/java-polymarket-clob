package com.polymarket.clob.order;

import java.util.Objects;

/**
 * 批量下单中的单条元素，与 py-clob-client {@code PostOrdersArgs} 对齐。
 *
 * @param order     已签名订单
 * @param orderType 该单的撮合策略（GTC/GTD/FOK/FAK）
 * @param postOnly  仅限 GTC/GTD；其余类型必须为 {@code false}
 */
public record PostOrdersEntry(SignedOrder order, OrderType orderType, boolean postOnly) {
    public PostOrdersEntry {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(orderType, "orderType");
        if (postOnly && orderType.isMarket()) {
            throw new IllegalArgumentException(
                    "postOnly can only be set for limit orders (GTC/GTD), got " + orderType);
        }
    }

    public static PostOrdersEntry of(SignedOrder order, OrderType orderType) {
        return new PostOrdersEntry(order, orderType, false);
    }

    public static PostOrdersEntry postOnly(SignedOrder order, OrderType orderType) {
        if (!orderType.isLimit()) {
            throw new IllegalArgumentException("postOnly requires GTC/GTD, got " + orderType);
        }
        return new PostOrdersEntry(order, orderType, true);
    }
}
