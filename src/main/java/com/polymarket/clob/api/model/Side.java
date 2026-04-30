package com.polymarket.clob.api.model;

/**
 * 订单方向。序列化为全大写字符串（{@code "BUY"} / {@code "SELL"}），与 CLOB 上游一致。
 *
 * <p>URL query 取值走 {@link #toQueryValue()}，一旦上游未来要求 lowercase 只需改这一个方法。</p>
 */
public enum Side {
    BUY,
    SELL;

    /** 用作 URL query 参数值。当前与 {@link #name()} 相同，预留集中改写点。 */
    public String toQueryValue() {
        return name();
    }

    /**
     * EIP-712 订单结构里的 {@code side} 字段编码（{@code Uint(8)}）：
     * {@code BUY = 0}，{@code SELL = 1}；与 python-order-utils 的
     * {@code py_order_utils.model.sides} 保持一致。
     *
     * <p>注意：这只是 EIP-712 签名阶段使用；wire JSON 中 {@code side} 仍以大写字符串
     * 形式出现（见 {@code Order#side}）。</p>
     */
    public int exchangeCode() {
        return this == BUY ? 0 : 1;
    }

    /** {@link #exchangeCode()} 的反解码，便于测试与反序列化容错。 */
    public static Side fromExchangeCode(int code) {
        if (code == 0) return BUY;
        if (code == 1) return SELL;
        throw new IllegalArgumentException("Unsupported side code: " + code);
    }
}
