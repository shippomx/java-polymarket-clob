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

class OrderJsonTest {

    private final ObjectMapper mapper = JsonCodec.objectMapper();

    private Order sampleOrder() {
        return Order.builder()
                .salt(new BigInteger("12345"))
                .maker(Address.fromHex("0x1111111111111111111111111111111111111111"))
                .signer(Address.fromHex("0x2222222222222222222222222222222222222222"))
                .taker(Address.fromHex("0x0000000000000000000000000000000000000000"))
                .tokenId(new BigInteger(
                        "52114319501245916944671373858265088651569731167955072076055664814935829495708"))
                .makerAmount(new BigInteger("100000000"))
                .takerAmount(new BigInteger("200000000"))
                .expiration(BigInteger.ZERO)
                .nonce(BigInteger.ONE)
                .feeRateBps(BigInteger.TEN)
                .side(Side.BUY)
                .signatureType(SignatureType.EOA)
                .build();
    }

    @Test
    void serialize_matchesPyClobClientShape() throws Exception {
        String json = mapper.writeValueAsString(sampleOrder());
        JsonNode node = mapper.readTree(json);

        // salt 保持数字（BigInteger 保精度）
        assertThat(node.get("salt").isNumber()).isTrue();
        assertThat(node.get("salt").bigIntegerValue()).isEqualTo(new BigInteger("12345"));

        // 地址字段是字符串（Address.@JsonValue → toHex）
        assertThat(node.get("maker").isTextual()).isTrue();
        assertThat(node.get("maker").asText()).isEqualToIgnoringCase("0x1111111111111111111111111111111111111111");

        // uint256 风字段序列化为字符串
        assertThat(node.get("tokenId").isTextual()).isTrue();
        assertThat(node.get("tokenId").asText()).isEqualTo(
                "52114319501245916944671373858265088651569731167955072076055664814935829495708");
        assertThat(node.get("makerAmount").asText()).isEqualTo("100000000");
        assertThat(node.get("takerAmount").asText()).isEqualTo("200000000");
        assertThat(node.get("expiration").asText()).isEqualTo("0");
        assertThat(node.get("nonce").asText()).isEqualTo("1");
        assertThat(node.get("feeRateBps").asText()).isEqualTo("10");

        // side: 大写字符串
        assertThat(node.get("side").asText()).isEqualTo("BUY");

        // signatureType: 数字
        assertThat(node.get("signatureType").isInt()).isTrue();
        assertThat(node.get("signatureType").asInt()).isEqualTo(0);
    }

    @Test
    void roundTrip_builderDeserialize() throws Exception {
        Order original = sampleOrder();
        String json = mapper.writeValueAsString(original);
        Order parsed = mapper.readValue(json, Order.class);
        assertThat(parsed).isEqualTo(original);
    }

    @Test
    void deserialize_acceptsBothStringAndNumberForUintFields() throws Exception {
        String json = "{\n" +
                "  \"salt\": \"12345\",\n" +
                "  \"maker\": \"0x1111111111111111111111111111111111111111\",\n" +
                "  \"signer\": \"0x2222222222222222222222222222222222222222\",\n" +
                "  \"taker\": \"0x0000000000000000000000000000000000000000\",\n" +
                "  \"tokenId\": 123,\n" +
                "  \"makerAmount\": \"1\",\n" +
                "  \"takerAmount\": 2,\n" +
                "  \"expiration\": 0,\n" +
                "  \"nonce\": \"1\",\n" +
                "  \"feeRateBps\": \"10\",\n" +
                "  \"side\": \"SELL\",\n" +
                "  \"signatureType\": 0\n" +
                "}";

        Order parsed = mapper.readValue(json, Order.class);
        assertThat(parsed.getSalt()).isEqualTo(new BigInteger("12345"));
        assertThat(parsed.getTokenId()).isEqualTo(BigInteger.valueOf(123));
        assertThat(parsed.getMakerAmount()).isEqualTo(BigInteger.ONE);
        assertThat(parsed.getSide()).isEqualTo(Side.SELL);
        assertThat(parsed.getSignatureType()).isEqualTo(SignatureType.EOA);
    }
}
