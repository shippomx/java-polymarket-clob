package com.polymarket.clob.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.clob.http.JsonCodec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTypeTest {

    private final ObjectMapper mapper = JsonCodec.objectMapper();

    @Test
    void serialize_uppercaseName() throws Exception {
        assertThat(mapper.writeValueAsString(OrderType.GTC)).isEqualTo("\"GTC\"");
        assertThat(mapper.writeValueAsString(OrderType.GTD)).isEqualTo("\"GTD\"");
        assertThat(mapper.writeValueAsString(OrderType.FOK)).isEqualTo("\"FOK\"");
        assertThat(mapper.writeValueAsString(OrderType.FAK)).isEqualTo("\"FAK\"");
    }

    @Test
    void deserialize_caseInsensitive() throws Exception {
        assertThat(mapper.readValue("\"GTC\"", OrderType.class)).isEqualTo(OrderType.GTC);
        assertThat(mapper.readValue("\"gtc\"", OrderType.class)).isEqualTo(OrderType.GTC);
        assertThat(mapper.readValue("\"Fok\"", OrderType.class)).isEqualTo(OrderType.FOK);
    }

    @Test
    void deserialize_unknown_throws() {
        assertThatThrownBy(() -> mapper.readValue("\"UNKNOWN\"", OrderType.class))
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void classification() {
        assertThat(OrderType.GTC.isLimit()).isTrue();
        assertThat(OrderType.GTD.isLimit()).isTrue();
        assertThat(OrderType.FOK.isLimit()).isFalse();
        assertThat(OrderType.FAK.isLimit()).isFalse();

        assertThat(OrderType.FOK.isMarket()).isTrue();
        assertThat(OrderType.FAK.isMarket()).isTrue();
        assertThat(OrderType.GTC.isMarket()).isFalse();
    }
}
