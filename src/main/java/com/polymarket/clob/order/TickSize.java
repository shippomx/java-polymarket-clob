package com.polymarket.clob.order;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 市场最小价格变动单位。与 Polymarket CLOB 的四档 tick 一一对应，对齐 py-clob-client
 * 的 {@code TickSize = Literal["0.1", "0.01", "0.001", "0.0001"]}。
 *
 * <p>每档 tick 绑定一套 {@link RoundConfig} —— 价格/数量/金额的小数位精度，是 OrderBuilder
 * 舍入时的输入依据。</p>
 *
 * <p>序列化输出沿用字符串（{@link JsonValue} → {@link #wireValue()}），反序列化支持字符串与
 * {@link BigDecimal} 等价形态（{@code "0.01"} / {@code 0.01} / {@code "0.0100"} 都接受）。</p>
 */
public enum TickSize {
    TS_0_1("0.1", new RoundConfig(1, 2, 3)),
    TS_0_01("0.01", new RoundConfig(2, 2, 4)),
    TS_0_001("0.001", new RoundConfig(3, 2, 5)),
    TS_0_0001("0.0001", new RoundConfig(4, 2, 6));

    private final String wire;
    private final BigDecimal value;
    private final RoundConfig rounding;

    TickSize(String wire, RoundConfig rounding) {
        this.wire = wire;
        this.value = new BigDecimal(wire);
        this.rounding = rounding;
    }

    /** 线上 wire 字符串（固定写法）。 */
    @JsonValue
    public String wireValue() {
        return wire;
    }

    /** 数值形态，便于计算。 */
    public BigDecimal value() {
        return value;
    }

    /** 该档 tick 绑定的 rounding config，暴露给 {@link OrderRounding}。 */
    public RoundConfig rounding() {
        return rounding;
    }

    /**
     * 反序列化。接受字符串（{@code "0.01"}）与数字字面量（{@code 0.01}），但值必须可精确匹配
     * 已知 tick——无法匹配会抛 {@link IllegalArgumentException}。
     */
    @JsonCreator
    public static TickSize fromWire(Object raw) {
        Objects.requireNonNull(raw, "tickSize");
        BigDecimal target;
        if (raw instanceof Number n) {
            target = new BigDecimal(n.toString());
        } else {
            target = new BigDecimal(raw.toString().trim());
        }
        for (TickSize t : values()) {
            if (t.value.compareTo(target) == 0) return t;
        }
        throw new IllegalArgumentException("Unknown tick size: " + raw);
    }

    /** 判断 {@code a} 是否比 {@code b} 更细粒度（数值更小）。 */
    public boolean isSmallerThan(TickSize other) {
        return value.compareTo(other.value) < 0;
    }
}
