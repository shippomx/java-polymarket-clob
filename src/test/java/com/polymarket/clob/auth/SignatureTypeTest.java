package com.polymarket.clob.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.clob.exception.ClobSerializationException;
import com.polymarket.clob.http.JsonCodec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignatureTypeTest {

    private final ObjectMapper mapper = JsonCodec.objectMapper();

    @Test
    void numericCodesAlignWithRust() {
        assertThat(SignatureType.EOA.code()).isEqualTo(0);
        assertThat(SignatureType.POLY_PROXY.code()).isEqualTo(1);
        assertThat(SignatureType.POLY_GNOSIS_SAFE.code()).isEqualTo(2);
        assertThat(SignatureType.POLY_1271.code()).isEqualTo(3);
    }

    @Test
    void toQueryValueIsDecimalString() {
        assertThat(SignatureType.EOA.toQueryValue()).isEqualTo("0");
        assertThat(SignatureType.POLY_PROXY.toQueryValue()).isEqualTo("1");
        assertThat(SignatureType.POLY_GNOSIS_SAFE.toQueryValue()).isEqualTo("2");
        assertThat(SignatureType.POLY_1271.toQueryValue()).isEqualTo("3");
    }

    @Test
    void poly1271RoundTrip() {
        assertThat(JsonCodec.writeValue(mapper, SignatureType.POLY_1271)).isEqualTo("3");
        assertThat(JsonCodec.readValue(mapper, "3", SignatureType.class))
                .isEqualTo(SignatureType.POLY_1271);
        assertThat(JsonCodec.readValue(mapper, "\"POLY_1271\"", SignatureType.class))
                .isEqualTo(SignatureType.POLY_1271);
    }

    @Test
    void serializesAsNumber() {
        assertThat(JsonCodec.writeValue(mapper, SignatureType.POLY_PROXY)).isEqualTo("1");
    }

    @Test
    void deserializesFromNumberOrName() {
        assertThat(JsonCodec.readValue(mapper, "0", SignatureType.class)).isEqualTo(SignatureType.EOA);
        assertThat(JsonCodec.readValue(mapper, "\"POLY_PROXY\"", SignatureType.class))
                .isEqualTo(SignatureType.POLY_PROXY);
    }

    @Test
    void rejectsUnknownName() {
        assertThatThrownBy(() -> JsonCodec.readValue(mapper, "\"LEDGER\"", SignatureType.class))
                .isInstanceOf(ClobSerializationException.class);
    }
}
