package com.polymarket.clob.order;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AmountTest {

    @Test
    void toAtoms_scalesBySixDecimals() {
        assertThat(Amount.toAtoms(new BigDecimal("100"))).isEqualTo(new BigInteger("100000000"));
        assertThat(Amount.toAtoms(new BigDecimal("1"))).isEqualTo(new BigInteger("1000000"));
        assertThat(Amount.toAtoms(new BigDecimal("0.5"))).isEqualTo(new BigInteger("500000"));
        assertThat(Amount.toAtoms(new BigDecimal("0.000001"))).isEqualTo(BigInteger.ONE);
    }

    @Test
    void toAtoms_truncatesBeyondSixDecimals() {
        assertThat(Amount.toAtoms(new BigDecimal("0.0000019"))).isEqualTo(BigInteger.ONE);
        assertThat(Amount.toAtoms(new BigDecimal("1.2345678"))).isEqualTo(new BigInteger("1234567"));
    }

    @Test
    void toAtoms_rejectsNegative() {
        assertThatThrownBy(() -> Amount.toAtoms(new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }

    @Test
    void toHuman_inverse() {
        assertThat(Amount.toHuman(new BigInteger("100000000")))
                .isEqualByComparingTo(new BigDecimal("100"));
        assertThat(Amount.toHuman(new BigInteger("500000")))
                .isEqualByComparingTo(new BigDecimal("0.5"));
    }

    @Test
    void usdc_sharesAliasesMatchToAtoms() {
        BigDecimal v = new BigDecimal("12.34");
        assertThat(Amount.usdc(v)).isEqualTo(Amount.toAtoms(v));
        assertThat(Amount.shares(v)).isEqualTo(Amount.toAtoms(v));
    }
}
