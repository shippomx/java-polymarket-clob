package com.polymarket.clob.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.clob.exception.ClobSerializationException;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.Hash32;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClobTypesJacksonTest {

    private final ObjectMapper mapper = JsonCodec.objectMapper();

    private static final String ADDR_LC = "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266";
    private static final Address ADDR = Address.fromHex(ADDR_LC);
    private static final Hash32 HASH = Hash32.fromHex(
            "0x00000000000000000000000000000000000000000000000000000000000000ff");

    @Test
    void addressRoundTripsThroughJson() {
        String json = JsonCodec.writeValue(mapper, ADDR);
        assertThat(json).isEqualTo("\"" + ADDR.toHex() + "\"");
        Address back = JsonCodec.readValue(mapper, json, Address.class);
        assertThat(back).isEqualTo(ADDR);
    }

    @Test
    void addressAcceptsLowercaseOnInput() {
        Address back = JsonCodec.readValue(mapper, "\"" + ADDR_LC + "\"", Address.class);
        assertThat(back).isEqualTo(ADDR);
    }

    @Test
    void hash32RoundTripsThroughJson() {
        String json = JsonCodec.writeValue(mapper, HASH);
        assertThat(json).isEqualTo("\"" + HASH.toHex() + "\"");
        Hash32 back = JsonCodec.readValue(mapper, json, Hash32.class);
        assertThat(back).isEqualTo(HASH);
    }

    @Test
    void addressUsableAsMapKey() {
        Map<Address, BigInteger> src = new LinkedHashMap<>();
        src.put(ADDR, new BigInteger("42"));

        String json = JsonCodec.writeValue(mapper, src);
        assertThat(json).contains(ADDR.toHex()).contains("42");

        Map<Address, BigInteger> back = JsonCodec.readValue(
                mapper, json, new TypeReference<>() {});
        assertThat(back).containsEntry(ADDR, new BigInteger("42"));
    }

    @Test
    void hash32UsableAsMapKey() {
        Map<Hash32, String> src = Map.of(HASH, "x");
        String json = JsonCodec.writeValue(mapper, src);
        Map<Hash32, String> back = JsonCodec.readValue(
                mapper, json, new TypeReference<>() {});
        assertThat(back).containsEntry(HASH, "x");
    }

    @Test
    void rejectsMalformedAddressKey() {
        String malformed = "{\"0xdead\": 1}";
        assertThatThrownBy(() -> JsonCodec.readValue(
                mapper, malformed, new TypeReference<Map<Address, Integer>>() {}))
                .isInstanceOf(ClobSerializationException.class);
    }
}
