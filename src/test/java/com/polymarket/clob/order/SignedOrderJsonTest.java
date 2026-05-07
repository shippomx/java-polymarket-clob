package com.polymarket.clob.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.http.JsonCodec;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignedOrderJsonTest {

    private final ObjectMapper mapper = JsonCodec.objectMapper();

    private Order baseOrder() {
        return Order.builder()
                .salt(new BigInteger("9999"))
                .maker(Address.fromHex("0x1111111111111111111111111111111111111111"))
                .signer(Address.fromHex("0x2222222222222222222222222222222222222222"))
                .taker(Address.fromHex("0x0000000000000000000000000000000000000000"))
                .tokenId(new BigInteger("42"))
                .makerAmount(new BigInteger("100000000"))
                .takerAmount(new BigInteger("200000000"))
                .expiration(BigInteger.ZERO)
                .nonce(BigInteger.ONE)
                .feeRateBps(BigInteger.TEN)
                .side(Side.SELL)
                .signatureType(SignatureType.EOA)
                .build();
    }

    private String fakeSig() {
        return "0x" + "ab".repeat(65);
    }

    @Test
    void serialize_flattensOrderFieldsPlusSignature() throws Exception {
        SignedOrder so = SignedOrder.of(baseOrder(), fakeSig());
        String json = mapper.writeValueAsString(so);
        JsonNode node = mapper.readTree(json);

        assertThat(node.has("order")).isFalse();
        assertThat(node.has("signature")).isTrue();
        assertThat(node.get("signature").asText()).isEqualTo(fakeSig());

        assertThat(node.get("salt").bigIntegerValue()).isEqualTo(new BigInteger("9999"));
        assertThat(node.get("side").asText()).isEqualTo("SELL");
        assertThat(node.get("signatureType").asInt()).isEqualTo(0);
        assertThat(node.get("maker").asText())
                .isEqualToIgnoringCase("0x1111111111111111111111111111111111111111");
        assertThat(node.get("tokenId").asText()).isEqualTo("42");
        assertThat(node.get("makerAmount").asText()).isEqualTo("100000000");
    }

    @Test
    void of_rejectsInvalidSignatureLength() {
        assertThatThrownBy(() -> SignedOrder.of(baseOrder(), "0x1234"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("132");
    }
}
