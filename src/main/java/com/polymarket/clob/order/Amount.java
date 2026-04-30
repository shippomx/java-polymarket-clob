package com.polymarket.clob.order;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * USDC / CTF shares 与 EIP-712 链上原值 {@code uint256} 的换算工具。
 *
 * <p>Polymarket 在 Polygon 上的抵押物 USDC（{@code 0x2791Bca1…}）与 CTF ERC-1155 份额都按
 * 10<sup>6</sup> 放大记账，因此这里统一用 {@link #DECIMALS} = 6 做缩放。所有 {@code BigDecimal}
 * → {@code BigInteger} 的换算使用 {@link RoundingMode#DOWN}——与 py-clob-client 的
 * {@code round_down} 语义一致（下单时只允许向下取整，避免超出 allowance）。</p>
 *
 * <p>无可变状态，无实例可创建，纯静态工具。</p>
 */
public final class Amount {

    /** 统一小数位数：USDC.e 与 CTF shares 都按 6 位放大（Polygon 上惯例）。 */
    public static final int DECIMALS = 6;

    /** 10<sup>{@value #DECIMALS}</sup>，避免反复新建。 */
    private static final BigInteger SCALE = BigInteger.TEN.pow(DECIMALS);

    /** {@link BigDecimal} 版本的 {@link #SCALE}，用于 {@code BigDecimal} 计算。 */
    private static final BigDecimal SCALE_DEC = new BigDecimal(SCALE);

    private Amount() {}

    /**
     * 把用户面数量（美元或份额）放大到链上原值。
     *
     * <p>等价于 py-clob-client 的 {@code to_token_decimals(x) = int(Decimal(x) * 10^6)}；
     * 负数直接抛 {@link IllegalArgumentException}——CLOB 不接受负金额。</p>
     */
    public static BigInteger toAtoms(BigDecimal human) {
        Objects.requireNonNull(human, "human");
        if (human.signum() < 0) {
            throw new IllegalArgumentException("amount must be non-negative: " + human.toPlainString());
        }
        return human.multiply(SCALE_DEC).setScale(0, RoundingMode.DOWN).toBigIntegerExact();
    }

    /**
     * 把链上原值缩回用户面 {@link BigDecimal}，小数位始终保留 {@link #DECIMALS}。
     */
    public static BigDecimal toHuman(BigInteger atoms) {
        Objects.requireNonNull(atoms, "atoms");
        return new BigDecimal(atoms).divide(SCALE_DEC, DECIMALS, RoundingMode.HALF_EVEN);
    }

    /** USDC 语义别名，只是为了调用方读起来更直观。 */
    public static BigInteger usdc(BigDecimal usd) {
        return toAtoms(usd);
    }

    /** CTF shares 语义别名。 */
    public static BigInteger shares(BigDecimal shareCount) {
        return toAtoms(shareCount);
    }
}
