package com.polymarket.clob.order;

/**
 * 订单数值舍入精度（小数位数）。对应 py-clob-client 的 {@code RoundConfig}：
 * {@code price} / {@code size} / {@code amount} 分别给价格、份额、金额的小数位精度。
 *
 * <p>各档 tick 的参数见 {@link TickSize#rounding()}。</p>
 */
public record RoundConfig(int price, int size, int amount) {
    public RoundConfig {
        if (price < 0 || size < 0 || amount < 0) {
            throw new IllegalArgumentException("round config digits must be non-negative");
        }
    }
}
