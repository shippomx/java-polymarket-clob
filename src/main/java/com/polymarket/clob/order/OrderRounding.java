package com.polymarket.clob.order;

import com.polymarket.clob.api.model.Side;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 订单金额与份额的舍入工具。
 *
 * <p>Java 端使用 {@link BigDecimal} 做精确十进制运算，因此不需要 py-clob-client 中
 * 「先 round_up 到 N+4 小数再 round_down 到 N 小数」的浮点兜底 —— 直接一次
 * {@link RoundingMode#DOWN} 即可。规则保持对等：
 * <ul>
 *   <li>价格：{@link RoundingMode#HALF_UP}（对齐 {@code round_normal}）。</li>
 *   <li>限价单份额（{@code size}）：{@link RoundingMode#DOWN}。</li>
 *   <li>市价单输入量（{@code amount}）：{@link RoundingMode#DOWN}。</li>
 *   <li>USDC/份额换算出的对侧金额：{@link RoundingMode#DOWN}（避免超出 allowance）。</li>
 * </ul>
 */
public final class OrderRounding {

    private OrderRounding() {}

    /**
     * 限价单的 maker/taker 原始数量计算，返回经 {@link Amount#toAtoms} 缩放后的 uint256。
     *
     * <p>语义：
     * <ul>
     *   <li>BUY: {@code maker} 是 USDC（user pays），{@code taker} 是 shares。</li>
     *   <li>SELL: {@code maker} 是 shares（user gives），{@code taker} 是 USDC。</li>
     * </ul>
     * </p>
     */
    public static Amounts limit(Side side,
                                BigDecimal size,
                                BigDecimal price,
                                RoundConfig cfg) {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(size, "size");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(cfg, "cfg");
        if (size.signum() <= 0) throw new IllegalArgumentException("size must be > 0: " + size);
        if (price.signum() <= 0) throw new IllegalArgumentException("price must be > 0: " + price);

        BigDecimal roundedPrice = price.setScale(cfg.price(), RoundingMode.HALF_UP);
        BigDecimal roundedSize = size.setScale(cfg.size(), RoundingMode.DOWN);

        return switch (side) {
            case BUY -> {
                BigDecimal rawTaker = roundedSize;                          // shares
                BigDecimal rawMaker = rawTaker.multiply(roundedPrice);      // USDC pre-round
                BigDecimal maker = rawMaker.setScale(cfg.amount(), RoundingMode.DOWN);
                yield new Amounts(Amount.usdc(maker), Amount.shares(rawTaker));
            }
            case SELL -> {
                BigDecimal rawMaker = roundedSize;                          // shares
                BigDecimal rawTaker = rawMaker.multiply(roundedPrice);      // USDC pre-round
                BigDecimal taker = rawTaker.setScale(cfg.amount(), RoundingMode.DOWN);
                yield new Amounts(Amount.shares(rawMaker), Amount.usdc(taker));
            }
        };
    }

    /**
     * 市价单的 maker/taker 原始数量计算。
     *
     * <p>语义：
     * <ul>
     *   <li>BUY: {@code amount} 是 USDC（user spends），需要按 price 换算出 shares。</li>
     *   <li>SELL: {@code amount} 是 shares（user sells），换算出 USDC。</li>
     * </ul>
     * 为避免数学运算过程中溢出或无限小数（例如 {@code 1 / 3}），USDC → shares 时保留
     * {@link RoundConfig#amount()} + 4 的中间精度再 {@link RoundingMode#DOWN}，对齐 Python 行为。
     */
    public static Amounts market(Side side,
                                 BigDecimal amount,
                                 BigDecimal price,
                                 RoundConfig cfg) {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(cfg, "cfg");
        if (amount.signum() <= 0) throw new IllegalArgumentException("amount must be > 0: " + amount);
        if (price.signum() <= 0) throw new IllegalArgumentException("price must be > 0: " + price);

        BigDecimal roundedPrice = price.setScale(cfg.price(), RoundingMode.HALF_UP);
        BigDecimal roundedAmount = amount.setScale(cfg.size(), RoundingMode.DOWN);

        return switch (side) {
            case BUY -> {
                BigDecimal rawMaker = roundedAmount;                         // USDC
                // 除法可能无限小数：先保留 amount+4 位再向下截
                BigDecimal intermediate = rawMaker.divide(
                        roundedPrice, cfg.amount() + 4, RoundingMode.DOWN);
                BigDecimal taker = intermediate.setScale(cfg.amount(), RoundingMode.DOWN);
                yield new Amounts(Amount.usdc(rawMaker), Amount.shares(taker));
            }
            case SELL -> {
                BigDecimal rawMaker = roundedAmount;                         // shares
                BigDecimal rawTaker = rawMaker.multiply(roundedPrice);       // USDC
                BigDecimal taker = rawTaker.setScale(cfg.amount(), RoundingMode.DOWN);
                yield new Amounts(Amount.shares(rawMaker), Amount.usdc(taker));
            }
        };
    }

    /** 配对结果：({@code makerAmount}, {@code takerAmount})，均为 uint256 原值。 */
    public record Amounts(java.math.BigInteger maker, java.math.BigInteger taker) {}
}
