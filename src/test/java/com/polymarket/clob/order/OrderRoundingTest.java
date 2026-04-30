package com.polymarket.clob.order;

import com.polymarket.clob.api.model.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OrderRounding 金值对照。
 *
 * <p>py-clob-client {@code ROUNDING_CONFIG} 的关键语义：
 * <ul>
 *   <li>BUY 限价：taker = size（份额精度）；maker = size * price，按 amount 小数位 DOWN 截断。</li>
 *   <li>SELL 限价：maker = size；taker = size * price，按 amount 小数位 DOWN 截断。</li>
 *   <li>BUY 市价：maker = USDC amount；taker = amount / price（按 amount 小数位 DOWN 截断）。</li>
 *   <li>SELL 市价：maker = shares amount；taker = amount * price，按 amount 小数位 DOWN 截断。</li>
 * </ul>
 * price 按 tick 小数位 HALF_UP；size 按 size 小数位 DOWN。</p>
 */
class OrderRoundingTest {

    private static final BigInteger USDC_1 = BigInteger.valueOf(1_000_000L);

    // ---------------- limit BUY ----------------

    @Test
    void limit_buy_tick_0_01_exact() {
        // size=100, price=0.5 → maker=50 USDC, taker=100 shares
        OrderRounding.Amounts a = OrderRounding.limit(
                Side.BUY, bd("100"), bd("0.5"), TickSize.TS_0_01.rounding());
        assertThat(a.maker()).isEqualTo(USDC_1.multiply(BigInteger.valueOf(50)));
        assertThat(a.taker()).isEqualTo(USDC_1.multiply(BigInteger.valueOf(100)));
    }

    @Test
    void limit_buy_tick_0_01_price_halfup_then_amount_down() {
        // price 0.333 at tick 0.01 → 0.33 (HALF_UP)
        // size 10.5 → 10.50 (2-dp size DOWN, no-op)
        // maker raw = 10.5 * 0.33 = 3.465; amount 4-dp DOWN → 3.4650
        // → 3_465_000 atoms
        OrderRounding.Amounts a = OrderRounding.limit(
                Side.BUY, bd("10.5"), bd("0.333"), TickSize.TS_0_01.rounding());
        assertThat(a.maker()).isEqualTo(BigInteger.valueOf(3_465_000L));
        assertThat(a.taker()).isEqualTo(BigInteger.valueOf(10_500_000L));
    }

    @Test
    void limit_buy_tick_0_001() {
        // tick 0.001 → price 3 dp, size 2 dp, amount 5 dp
        // price 0.1234 HALF_UP at 3dp → 0.123
        // size 1.239 → 1.23 (2dp DOWN)
        // maker raw = 1.23 * 0.123 = 0.15129; amount 5dp DOWN → 0.15129 → 151_290
        OrderRounding.Amounts a = OrderRounding.limit(
                Side.BUY, bd("1.239"), bd("0.1234"), TickSize.TS_0_001.rounding());
        assertThat(a.maker()).isEqualTo(BigInteger.valueOf(151_290L));
        assertThat(a.taker()).isEqualTo(BigInteger.valueOf(1_230_000L));
    }

    // ---------------- limit SELL ----------------

    @Test
    void limit_sell_tick_0_01_exact() {
        // size=100, price=0.5 → maker=100 shares, taker=50 USDC
        OrderRounding.Amounts a = OrderRounding.limit(
                Side.SELL, bd("100"), bd("0.5"), TickSize.TS_0_01.rounding());
        assertThat(a.maker()).isEqualTo(USDC_1.multiply(BigInteger.valueOf(100)));
        assertThat(a.taker()).isEqualTo(USDC_1.multiply(BigInteger.valueOf(50)));
    }

    @Test
    void limit_sell_truncates_taker_down() {
        // size 7.89 → 7.89 (2dp)
        // price 0.257 at tick 0.01 → 0.26 HALF_UP
        // taker raw = 7.89 * 0.26 = 2.0514; amount 4dp DOWN → 2.0514 → 2_051_400
        OrderRounding.Amounts a = OrderRounding.limit(
                Side.SELL, bd("7.89"), bd("0.257"), TickSize.TS_0_01.rounding());
        assertThat(a.maker()).isEqualTo(BigInteger.valueOf(7_890_000L));
        assertThat(a.taker()).isEqualTo(BigInteger.valueOf(2_051_400L));
    }

    // ---------------- market BUY ----------------

    @Test
    void market_buy_tick_0_01() {
        // amount=$100, price=0.5 → maker=100 USDC, taker=100/0.5=200 shares
        OrderRounding.Amounts a = OrderRounding.market(
                Side.BUY, bd("100"), bd("0.5"), TickSize.TS_0_01.rounding());
        assertThat(a.maker()).isEqualTo(BigInteger.valueOf(100_000_000L));
        assertThat(a.taker()).isEqualTo(BigInteger.valueOf(200_000_000L));
    }

    @Test
    void market_buy_truncates_taker_down_tick_0_01() {
        // amount=$10, price=0.33 → taker = 10/0.33 = 30.303030... → amount 4dp DOWN → 30.3030
        // → 30_303_000
        OrderRounding.Amounts a = OrderRounding.market(
                Side.BUY, bd("10"), bd("0.33"), TickSize.TS_0_01.rounding());
        assertThat(a.maker()).isEqualTo(BigInteger.valueOf(10_000_000L));
        assertThat(a.taker()).isEqualTo(BigInteger.valueOf(30_303_000L));
    }

    // ---------------- market SELL ----------------

    @Test
    void market_sell_tick_0_01() {
        // amount=200 shares, price=0.5 → maker=200 shares, taker=100 USDC
        OrderRounding.Amounts a = OrderRounding.market(
                Side.SELL, bd("200"), bd("0.5"), TickSize.TS_0_01.rounding());
        assertThat(a.maker()).isEqualTo(BigInteger.valueOf(200_000_000L));
        assertThat(a.taker()).isEqualTo(BigInteger.valueOf(100_000_000L));
    }

    @Test
    void market_sell_truncates_taker_down() {
        // amount=5.5 shares, price=0.37 → taker = 5.5 * 0.37 = 2.035, amount 4dp DOWN → 2.035 → 2_035_000
        OrderRounding.Amounts a = OrderRounding.market(
                Side.SELL, bd("5.5"), bd("0.37"), TickSize.TS_0_01.rounding());
        assertThat(a.maker()).isEqualTo(BigInteger.valueOf(5_500_000L));
        assertThat(a.taker()).isEqualTo(BigInteger.valueOf(2_035_000L));
    }

    // ---------------- validation ----------------

    @Test
    void rejects_non_positive_size() {
        assertThatThrownBy(() -> OrderRounding.limit(
                Side.BUY, BigDecimal.ZERO, bd("0.5"), TickSize.TS_0_01.rounding()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_non_positive_price() {
        assertThatThrownBy(() -> OrderRounding.limit(
                Side.BUY, bd("1"), bd("0"), TickSize.TS_0_01.rounding()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tick_size_ordering() {
        assertThat(TickSize.TS_0_0001.isSmallerThan(TickSize.TS_0_001)).isTrue();
        assertThat(TickSize.TS_0_1.isSmallerThan(TickSize.TS_0_01)).isFalse();
    }

    private static BigDecimal bd(String s) { return new BigDecimal(s); }
}
