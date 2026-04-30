package com.polymarket.clob.api.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SideTest {

    @Test
    void exchangeCode_matchesPyOrderUtils() {
        assertThat(Side.BUY.exchangeCode()).isEqualTo(0);
        assertThat(Side.SELL.exchangeCode()).isEqualTo(1);
    }

    @Test
    void fromExchangeCode_roundTrip() {
        for (Side s : Side.values()) {
            assertThat(Side.fromExchangeCode(s.exchangeCode())).isEqualTo(s);
        }
    }

    @Test
    void fromExchangeCode_rejectsUnknown() {
        assertThatThrownBy(() -> Side.fromExchangeCode(2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2");
        assertThatThrownBy(() -> Side.fromExchangeCode(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toQueryValue_unchanged() {
        assertThat(Side.BUY.toQueryValue()).isEqualTo("BUY");
        assertThat(Side.SELL.toQueryValue()).isEqualTo("SELL");
    }
}
