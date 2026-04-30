package com.polymarket.clob.api;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.polymarket.clob.api.model.MarketResponse;
import com.polymarket.clob.api.model.MidpointResponse;
import com.polymarket.clob.api.model.OrderBookSnapshot;
import com.polymarket.clob.api.model.PriceResponse;
import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.http.HttpTransport;
import com.polymarket.clob.http.JsonCodec;
import com.polymarket.clob.http.SharedHttpClient;
import com.polymarket.clob.order.TickSize;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketDataApiImplTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .failOnUnmatchedRequests(true)
            .build();

    private MarketDataApi api() {
        HttpTransport t = new HttpTransport(
                URI.create(wm.baseUrl() + "/"),
                SharedHttpClient.INSTANCE,
                JsonCodec.objectMapper(),
                Duration.ofSeconds(5));
        return new MarketDataApiImpl(t);
    }

    @Test
    void ok() {
        wm.stubFor(get(urlEqualTo("/"))
                .willReturn(okJson("\"OK\"")));

        assertThat(api().ok().join()).isEqualTo("OK");
    }

    @Test
    void serverTime() {
        wm.stubFor(get(urlEqualTo("/time"))
                .willReturn(okJson("1700000000")));

        assertThat(api().serverTime().join()).isEqualTo(1_700_000_000L);
    }

    @Test
    void serverVersion_returnsV2() {
        // Polygon 主网 2026-04-28 上线后返回 2
        wm.stubFor(get(urlEqualTo("/version"))
                .willReturn(okJson("{\"version\":2}")));

        assertThat(api().serverVersion().join()).isEqualTo(2);
    }

    @Test
    void serverVersion_returnsV1() {
        // 旧测试网/历史快照仍能回 1，客户端不应硬编码
        wm.stubFor(get(urlEqualTo("/version"))
                .willReturn(okJson("{\"version\":1}")));

        assertThat(api().serverVersion().join()).isEqualTo(1);
    }

    @Test
    void midpoint() {
        wm.stubFor(get(urlPathEqualTo("/midpoint"))
                .withQueryParam("token_id", equalTo("42"))
                .willReturn(okJson("{\"mid\":\"0.5\"}")));

        MidpointResponse m = api().getMidpoint("42").join();
        assertThat(m.getMid()).isEqualByComparingTo("0.5");
    }

    @Test
    void priceBuy() {
        wm.stubFor(get(urlPathEqualTo("/price"))
                .withQueryParam("token_id", equalTo("42"))
                .withQueryParam("side", equalTo("BUY"))
                .willReturn(okJson("{\"price\":\"0.37\"}")));

        PriceResponse p = api().getPrice("42", Side.BUY).join();
        assertThat(p.getPrice()).isEqualByComparingTo("0.37");
    }

    @Test
    void priceSell() {
        wm.stubFor(get(urlPathEqualTo("/price"))
                .withQueryParam("token_id", equalTo("42"))
                .withQueryParam("side", equalTo("SELL"))
                .willReturn(okJson("{\"price\":\"0.63\"}")));

        PriceResponse p = api().getPrice("42", Side.SELL).join();
        assertThat(p.getPrice()).isEqualByComparingTo("0.63");
    }

    @Test
    void orderBook() {
        wm.stubFor(get(urlPathEqualTo("/book"))
                .withQueryParam("token_id", equalTo("42"))
                .willReturn(okJson("""
                        {"market":"0x00","asset_id":"42","timestamp":"1700000000000",
                         "hash":"h","bids":[],"asks":[]}""")));

        OrderBookSnapshot b = api().getOrderBook("42").join();
        assertThat(b.getAssetId()).isEqualTo("42");
        assertThat(b.getBids()).isEmpty();
        assertThat(b.getAsks()).isEmpty();
    }

    @Test
    void market() {
        wm.stubFor(get(urlPathEqualTo("/markets/0xabc"))
                .willReturn(okJson("""
                        {"active":true,"closed":false,"archived":false,"accepting_orders":true,
                         "enable_order_book":true,"minimum_order_size":"5","minimum_tick_size":"0.01",
                         "condition_id":"0xabc","question_id":"0xdef","market_slug":"slug"}""")));

        MarketResponse m = api().getMarket("0xabc").join();
        assertThat(m.getConditionId()).isEqualTo("0xabc");
        assertThat(m.getActive()).isTrue();
        assertThat(m.getAcceptingOrders()).isTrue();
        assertThat(m.getMinimumTickSize()).isEqualByComparingTo("0.01");
    }

    @Test
    void marketConditionIdWithSpecialCharsEncoded() {
        // M1: conditionId 含 '/' / 空格 / '?' 等必须被 path-encode，不能破坏 URI 结构
        wm.stubFor(get(urlPathEqualTo("/markets/a%20b%2Fc"))
                .willReturn(okJson("{\"condition_id\":\"a b/c\",\"active\":true}")));

        MarketResponse m = api().getMarket("a b/c").join();
        assertThat(m.getConditionId()).isEqualTo("a b/c");
    }

    @Test
    void nullTokenIdRejected() {
        assertThatThrownBy(() -> api().getMidpoint(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> api().getPrice(null, Side.BUY))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> api().getOrderBook(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullSideRejected() {
        assertThatThrownBy(() -> api().getPrice("42", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructorRejectsNullTransport() {
        assertThatThrownBy(() -> new MarketDataApiImpl(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void tickSizeParsesString() {
        wm.stubFor(get(urlPathEqualTo("/tick-size"))
                .withQueryParam("token_id", equalTo("42"))
                .willReturn(okJson("{\"minimum_tick_size\":\"0.01\"}")));

        assertThat(api().getTickSize("42").join()).isEqualTo(TickSize.TS_0_01);
    }

    @Test
    void tickSizeParsesNumber() {
        // 服务端偶尔返回 number 而非 string；枚举反序列化需同时兼容
        wm.stubFor(get(urlPathEqualTo("/tick-size"))
                .withQueryParam("token_id", equalTo("42"))
                .willReturn(okJson("{\"minimum_tick_size\":0.001}")));

        assertThat(api().getTickSize("42").join()).isEqualTo(TickSize.TS_0_001);
    }

    @Test
    void negRiskReturnsBoolean() {
        wm.stubFor(get(urlPathEqualTo("/neg-risk"))
                .withQueryParam("token_id", equalTo("42"))
                .willReturn(okJson("{\"neg_risk\":true}")));

        assertThat(api().getNegRisk("42").join()).isTrue();
    }

    @Test
    void negRiskFalse() {
        wm.stubFor(get(urlPathEqualTo("/neg-risk"))
                .withQueryParam("token_id", equalTo("77"))
                .willReturn(okJson("{\"neg_risk\":false}")));

        assertThat(api().getNegRisk("77").join()).isFalse();
    }

    @Test
    void feeRateBpsExtractsBaseFee() {
        wm.stubFor(get(urlPathEqualTo("/fee-rate"))
                .withQueryParam("token_id", equalTo("42"))
                .willReturn(okJson("{\"base_fee\":1000}")));

        assertThat(api().getFeeRateBps("42").join()).isEqualTo(1000);
    }
}
