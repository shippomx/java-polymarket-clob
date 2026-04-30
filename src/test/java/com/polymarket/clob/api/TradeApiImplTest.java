package com.polymarket.clob.api;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.api.model.TradeStatusType;
import com.polymarket.clob.api.model.TraderSide;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.auth.L2HeaderBuilder;
import com.polymarket.clob.http.HttpTransport;
import com.polymarket.clob.http.JsonCodec;
import com.polymarket.clob.http.SharedHttpClient;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.Hash32;
import com.polymarket.clob.trade.BuilderTrade;
import com.polymarket.clob.trade.Trade;
import com.polymarket.clob.trade.TradesRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TradeApiImpl WireMock 集成测试。覆盖：
 * <ul>
 *   <li>{@code GET /data/trades} 游标分页 + query 过滤</li>
 *   <li>{@code GET /builder/trades} 额外合并 Builder 头</li>
 * </ul>
 * <p>JSON 字段形态参考 Rust {@code TradeResponse} 与线上实际返回（截取自 py-clob-client
 * debug 日志）。</p>
 */
class TradeApiImplTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .failOnUnmatchedRequests(true)
            .build();

    private static final ApiCredentials CREDS = new ApiCredentials(
            "00000000-0000-0000-0000-000000000000",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    private static final Address MAKER =
            Address.fromHex("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266");

    private TradeApi api() {
        HttpTransport t = new HttpTransport(
                URI.create(wm.baseUrl() + "/"),
                SharedHttpClient.INSTANCE,
                JsonCodec.objectMapper(),
                Duration.ofSeconds(5));
        return new TradeApiImpl(t);
    }

    private static final String PAGE_1 = """
            {"data":[
              {"id":"t1","taker_order_id":"o1",
               "market":"0x1111111111111111111111111111111111111111111111111111111111111111",
               "asset_id":"1","side":"BUY","size":"3.5","fee_rate_bps":"0",
               "price":"0.5","status":"MATCHED","match_time":"1700000000","last_update":"1700000001",
               "outcome":"YES","bucket_index":0,"owner":"0x0",
               "maker_address":"0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
               "maker_orders":[],
               "transaction_hash":"0x2222222222222222222222222222222222222222222222222222222222222222",
               "trader_side":"TAKER"}
            ],"next_cursor":"NEXT"}
            """;

    private static final String PAGE_2 = """
            {"data":[
              {"id":"t2","taker_order_id":"o2",
               "market":"0x1111111111111111111111111111111111111111111111111111111111111111",
               "asset_id":"2","side":"SELL","size":"1","fee_rate_bps":"100",
               "price":"0.6","status":"MINED","match_time":"1700000010","last_update":"1700000011",
               "outcome":"NO","bucket_index":1,"owner":"0x0",
               "maker_address":"0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
               "maker_orders":[],
               "transaction_hash":"0x3333333333333333333333333333333333333333333333333333333333333333",
               "trader_side":"MAKER"}
            ],"next_cursor":"LTE="}
            """;

    @Test
    void getTradesPagesUntilTerminalCursorAndParsesEnums() {
        wm.stubFor(get(urlPathEqualTo("/data/trades"))
                .withQueryParam("next_cursor", absent())
                .withQueryParam("maker", equalTo(MAKER.toLowerHex()))
                .withHeader(L2HeaderBuilder.POLY_SIGNATURE, matching(".+"))
                .willReturn(okJson(PAGE_1)));
        wm.stubFor(get(urlPathEqualTo("/data/trades"))
                .withQueryParam("next_cursor", equalTo("NEXT"))
                .withQueryParam("maker", equalTo(MAKER.toLowerHex()))
                .willReturn(okJson(PAGE_2)));

        TradesRequest req = TradesRequest.builder().makerAddress(MAKER).build();
        List<Trade> trades = api().getTrades(MAKER, CREDS, 1L, req).collect(Collectors.toList());

        assertThat(trades).hasSize(2);
        Trade t1 = trades.get(0);
        assertThat(t1.getId()).isEqualTo("t1");
        assertThat(t1.getSide()).isEqualTo(Side.BUY);
        assertThat(t1.getStatus()).isEqualTo(TradeStatusType.MATCHED);
        assertThat(t1.getTraderSide()).isEqualTo(TraderSide.TAKER);
        assertThat(t1.getPrice()).isEqualByComparingTo("0.5");
        assertThat(t1.getAssetId()).isEqualTo(new BigInteger("1"));
        assertThat(t1.getMatchTime()).isEqualTo(1_700_000_000L);
        assertThat(trades.get(1).getStatus()).isEqualTo(TradeStatusType.MINED);
        assertThat(trades.get(1).getTraderSide()).isEqualTo(TraderSide.MAKER);
    }

    @Test
    void getBuilderTradesMergesBuilderHeadersAndSignsBuilderPath() {
        Map<String, String> builderHeaders = Map.of(
                "POLY_BUILDER_API_KEY", "builder-key",
                "POLY_BUILDER_PASSPHRASE", "builder-pass",
                "POLY_BUILDER_SIGNATURE", "sig",
                "POLY_BUILDER_TIMESTAMP", "1");

        String builderPage = """
                {"data":[
                  {"id":"b1","takerOrderHash":"0x4444444444444444444444444444444444444444444444444444444444444444",
                   "tradeType":"LIMIT_MATCHED","side":"BUY","size":"1","price":"0.42",
                   "status":"MATCHED","matchTime":1700000020,"lastUpdate":1700000021,
                   "bucketIndex":0,"takerOrderId":"o9","outcome":"YES",
                   "assetId":"9","asset":"9","trader":"0xabc",
                   "market":"0x1111111111111111111111111111111111111111111111111111111111111111",
                   "feeRateBps":"0","makerOrders":[],
                   "transactionHash":"0x5555555555555555555555555555555555555555555555555555555555555555",
                   "traderSide":"TAKER"}
                ],"next_cursor":"LTE="}
                """;

        wm.stubFor(get(urlPathEqualTo("/builder/trades"))
                .withHeader("POLY_BUILDER_API_KEY", equalTo("builder-key"))
                .withHeader("POLY_BUILDER_SIGNATURE", equalTo("sig"))
                .withHeader(L2HeaderBuilder.POLY_SIGNATURE, matching(".+"))
                .willReturn(okJson(builderPage)));

        List<BuilderTrade> trades = api()
                .getBuilderTrades(MAKER, CREDS, 1L, TradesRequest.none(), builderHeaders)
                .collect(Collectors.toList());

        assertThat(trades).hasSize(1);
        BuilderTrade bt = trades.get(0);
        assertThat(bt.getId()).isEqualTo("b1");
        assertThat(bt.getSide()).isEqualTo(Side.BUY);
        assertThat(bt.getStatus()).isEqualTo(TradeStatusType.MATCHED);
        assertThat(bt.getTakerOrderHash().toHex())
                .isEqualTo("0x4444444444444444444444444444444444444444444444444444444444444444");
    }

    @Test
    void tradesRequestQueryParamsSerializeAllFields() {
        Hash32 market = Hash32.fromHex(
                "0x1111111111111111111111111111111111111111111111111111111111111111");
        TradesRequest req = TradesRequest.builder()
                .id("trade-x")
                .takerAddress(MAKER)
                .makerAddress(MAKER)
                .market(market)
                .assetId(new BigInteger("42"))
                .before(1700000000L)
                .after(1600000000L)
                .build();
        Map<String, String> q = req.toQueryParams();
        assertThat(q).containsEntry("id", "trade-x")
                .containsEntry("taker", MAKER.toLowerHex())
                .containsEntry("maker", MAKER.toLowerHex())
                .containsEntry("market", market.toHex())
                .containsEntry("asset_id", "42")
                .containsEntry("before", "1700000000")
                .containsEntry("after", "1600000000");
    }
}
