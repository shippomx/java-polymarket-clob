package com.polymarket.clob.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.clob.exception.ClobSerializationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonCodecTest {

    @Test
    void ignoresUnknownFields() {
        ObjectMapper m = JsonCodec.objectMapper();
        String json = "{\"mid\":0.25,\"extra\":true}";
        Midpoint mid = JsonCodec.readValue(m, json, Midpoint.class);
        assertThat(mid.mid()).isEqualByComparingTo("0.25");
    }

    @Test
    void deserializesBigDecimalLosslessly() {
        ObjectMapper m = JsonCodec.objectMapper();
        Midpoint mid = JsonCodec.readValue(m, "{\"mid\":\"0.123456789012345678\"}", Midpoint.class);
        assertThat(mid.mid()).isEqualByComparingTo(new BigDecimal("0.123456789012345678"));
    }

    @Test
    void deserializesFloatLiteralAsBigDecimal() {
        ObjectMapper m = JsonCodec.objectMapper();
        // 无引号浮点字面量也必须走 BigDecimal 路径，否则 0.1 会被解析成 0.10000000000000000555...
        Midpoint mid = JsonCodec.readValue(m, "{\"mid\":0.1}", Midpoint.class);
        assertThat(mid.mid()).isEqualByComparingTo(new BigDecimal("0.1"));
    }

    @Test
    void wrapsJsonParseError() {
        ObjectMapper m = JsonCodec.objectMapper();
        assertThatThrownBy(() -> JsonCodec.readValue(m, "not-json", Midpoint.class))
                .isInstanceOf(ClobSerializationException.class)
                .hasMessageContaining("Midpoint");
    }

    @Test
    void wrapsTypeRefParseError() {
        ObjectMapper m = JsonCodec.objectMapper();
        assertThatThrownBy(() ->
                JsonCodec.readValue(m, "not-json", new TypeReference<List<Midpoint>>() {}))
                .isInstanceOf(ClobSerializationException.class);
    }

    @Test
    void readsTypeReference() {
        ObjectMapper m = JsonCodec.objectMapper();
        String json = "[{\"mid\":0.25},{\"mid\":0.5}]";
        List<Midpoint> list = JsonCodec.readValue(m, json, new TypeReference<List<Midpoint>>() {});
        assertThat(list).hasSize(2);
        assertThat(list.get(1).mid()).isEqualByComparingTo("0.5");
    }

    @Test
    void writeValueSerializes() {
        ObjectMapper m = JsonCodec.objectMapper();
        String json = JsonCodec.writeValue(m, Map.of("a", 1));
        assertThat(json).isEqualTo("{\"a\":1}");
    }

    @Test
    void writeValueWrapsFailure() {
        ObjectMapper m = JsonCodec.objectMapper();
        // 循环引用可让 Jackson 抛 JsonMappingException
        var self = new java.util.HashMap<String, Object>();
        self.put("me", self);
        assertThatThrownBy(() -> JsonCodec.writeValue(m, self))
                .isInstanceOf(ClobSerializationException.class);
    }

    @Test
    void objectMapperIsSingleton() {
        assertThat(JsonCodec.objectMapper()).isSameAs(JsonCodec.objectMapper());
    }

    record Midpoint(BigDecimal mid) {}
}
