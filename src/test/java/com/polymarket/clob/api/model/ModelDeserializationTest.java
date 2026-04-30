package com.polymarket.clob.api.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.clob.http.JsonCodec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ModelDeserializationTest {

    private final ObjectMapper mapper = JsonCodec.objectMapper();

    @Test
    void midpointResponse() {
        MidpointResponse m = JsonCodec.readValue(mapper, "{\"mid\":\"0.5\"}", MidpointResponse.class);
        assertThat(m.getMid()).isEqualByComparingTo("0.5");
    }

    @Test
    void priceResponse() {
        PriceResponse p = JsonCodec.readValue(mapper, "{\"price\":\"0.37\"}", PriceResponse.class);
        assertThat(p.getPrice()).isEqualByComparingTo("0.37");
    }

    @Test
    void sideEnumUppercase() {
        assertThat(JsonCodec.readValue(mapper, "\"BUY\"", Side.class)).isEqualTo(Side.BUY);
        assertThat(JsonCodec.readValue(mapper, "\"SELL\"", Side.class)).isEqualTo(Side.SELL);
    }

    @Test
    void orderBookSnapshotFromFixture() throws IOException {
        String json;
        try (InputStream in = getClass().getResourceAsStream("/fixtures/orderbook_sample.json")) {
            assertThat(in).as("classpath fixture missing").isNotNull();
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        OrderBookSnapshot book = JsonCodec.readValue(mapper, json, OrderBookSnapshot.class);

        assertThat(book.getMarket()).startsWith("0xaabbcc");
        assertThat(book.getAssetId()).isEqualTo("1234567890");
        assertThat(book.getTimestampMillis()).isEqualTo("1700000000000");
        assertThat(book.getHash()).isEqualTo("deadbeef");
        assertThat(book.getBids()).hasSize(2);
        assertThat(book.getBids().get(0).getPrice()).isEqualByComparingTo(new BigDecimal("0.49"));
        assertThat(book.getBids().get(0).getSize()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(book.getAsks()).hasSize(1);
        assertThat(book.getAsks().get(0).getPrice()).isEqualByComparingTo(new BigDecimal("0.51"));
        assertThat(book.getMinOrderSize()).isEqualByComparingTo(new BigDecimal("5"));
        assertThat(book.getTickSize()).isEqualByComparingTo(new BigDecimal("0.01"));
        assertThat(book.getNegRisk()).isFalse();
        assertThat(book.getLastTradePrice()).isEqualByComparingTo(new BigDecimal("0.50"));
    }

    @Test
    void orderBookSnapshotTolerantToMissingPlan3Fields() {
        // 旧快照无 min_order_size / neg_risk / tick_size 时，字段应为 null，不抛异常
        String json = """
                {"market":"0x00","asset_id":"1","timestamp":"1","hash":"h",
                 "bids":[],"asks":[]}""";
        OrderBookSnapshot book = JsonCodec.readValue(mapper, json, OrderBookSnapshot.class);
        assertThat(book.getMinOrderSize()).isNull();
        assertThat(book.getNegRisk()).isNull();
        assertThat(book.getTickSize()).isNull();
        assertThat(book.getLastTradePrice()).isNull();
    }

    @Test
    void marketResponseTokensAndNegRisk() {
        String json = """
                {"condition_id":"0xabc","question":"will X happen?",
                 "neg_risk":true,"end_date_iso":"2026-12-31T00:00:00Z",
                 "tags":["politics","us"],
                 "tokens":[
                    {"token_id":"100","outcome":"YES","price":"0.55","winner":false},
                    {"token_id":"101","outcome":"NO","price":"0.45","winner":false}
                 ]}""";
        MarketResponse m = JsonCodec.readValue(mapper, json, MarketResponse.class);
        assertThat(m.getQuestion()).isEqualTo("will X happen?");
        assertThat(m.getNegRisk()).isTrue();
        assertThat(m.getEndDateIso()).isEqualTo("2026-12-31T00:00:00Z");
        assertThat(m.getTags()).containsExactly("politics", "us");
        assertThat(m.getTokens()).hasSize(2);
        assertThat(m.getTokens().get(0).getTokenId()).isEqualTo("100");
        assertThat(m.getTokens().get(0).getOutcome()).isEqualTo("YES");
        assertThat(m.getTokens().get(0).getPrice()).isEqualByComparingTo(new BigDecimal("0.55"));
        assertThat(m.getTokens().get(0).getWinner()).isFalse();
    }

    @Test
    void sideLowercaseRejected() {
        // N7: 上游协议全大写，lowercase 应当显式报错而不是被悄悄 coerce
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> JsonCodec.readValue(mapper, "\"buy\"", Side.class))
                .isInstanceOf(com.polymarket.clob.exception.ClobSerializationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> JsonCodec.readValue(mapper, "\"Buy\"", Side.class))
                .isInstanceOf(com.polymarket.clob.exception.ClobSerializationException.class);
    }

    @Test
    void marketResponsePartialFields() {
        String json = """
                {"active":true,"closed":false,"minimum_tick_size":"0.01",
                 "condition_id":"0xabc","market_slug":"slug"}""";
        MarketResponse m = JsonCodec.readValue(mapper, json, MarketResponse.class);
        assertThat(m.getActive()).isTrue();
        assertThat(m.getClosed()).isFalse();
        assertThat(m.getMinimumTickSize()).isEqualByComparingTo("0.01");
        assertThat(m.getConditionId()).isEqualTo("0xabc");
        assertThat(m.getMarketSlug()).isEqualTo("slug");
        // 未出现的字段为 null
        assertThat(m.getEnableOrderBook()).isNull();
        assertThat(m.getDescription()).isNull();
    }

    @Test
    void orderSummaryUsesBigDecimal() {
        OrderSummary s = JsonCodec.readValue(mapper,
                "{\"price\":\"0.123456789012345678\",\"size\":\"10000000\"}",
                OrderSummary.class);
        assertThat(s.getPrice()).isEqualByComparingTo(new BigDecimal("0.123456789012345678"));
        assertThat(s.getSize()).isEqualByComparingTo(new BigDecimal("10000000"));
    }
}
