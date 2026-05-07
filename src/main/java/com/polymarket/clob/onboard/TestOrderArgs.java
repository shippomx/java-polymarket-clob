package com.polymarket.clob.onboard;

import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.order.OrderType;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Onboarder 可选下单参数。{@link Onboarder#run(com.polymarket.clob.auth.Signer)}
 * 在配置中提供本对象时执行步骤 7（POLY_1271 下单），否则跳过。
 */
public record TestOrderArgs(
        BigInteger tokenId,
        Side side,
        BigDecimal price,
        BigDecimal size,
        OrderType orderType,
        String tickSize,
        boolean negRisk
) {}
