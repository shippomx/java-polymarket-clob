package com.polymarket.clob.api;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.auth.L2HeaderBuilder;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.http.HttpTransport;
import com.polymarket.clob.http.JsonCodec;
import com.polymarket.clob.http.SharedHttpClient;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.Hash32;
import com.polymarket.clob.order.CancelMarketOrdersRequest;
import com.polymarket.clob.order.CancelResponse;
import com.polymarket.clob.order.OpenOrder;
import com.polymarket.clob.order.OpenOrderParams;
import com.polymarket.clob.order.Order;
import com.polymarket.clob.order.OrderScoringResponse;
import com.polymarket.clob.order.OrderType;
import com.polymarket.clob.order.OrdersScoringResponse;
import com.polymarket.clob.order.PostOrderResponse;
import com.polymarket.clob.order.PostOrdersEntry;
import com.polymarket.clob.order.OrderV2;
import com.polymarket.clob.order.SignedOrder;
import com.polymarket.clob.order.SignedOrderV2;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OrderApiImpl WireMock 集成测试。覆盖：
 * <ul>
 *   <li>POST /order / POST /orders body 形状 + L2 头</li>
 *   <li>DELETE /order / /orders / /cancel-all body + 无 body 语义</li>
 *   <li>GET /data/order/{id} / GET /data/orders（游标分页）</li>
 * </ul>
 *
 * <p>使用有效的公开测试密钥签出的订单作为固定 payload；上游响应按 py-clob-client 观察到的形态
 * 构造。</p>
 */
class OrderApiImplTest {

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

    private OrderApi api() {
        HttpTransport t = new HttpTransport(
                URI.create(wm.baseUrl() + "/"),
                SharedHttpClient.INSTANCE,
                JsonCodec.objectMapper(),
                Duration.ofSeconds(5));
        return new OrderApiImpl(t);
    }

    private SignedOrder signedOrder() {
        Order order = Order.builder()
                .salt(BigInteger.valueOf(1001L))
                .maker(MAKER)
                .signer(MAKER)
                .taker(Address.ZERO)
                .tokenId(BigInteger.valueOf(1234L))
                .makerAmount(BigInteger.valueOf(100_000_000L))
                .takerAmount(BigInteger.valueOf(50_000_000L))
                .expiration(BigInteger.ZERO)
                .nonce(BigInteger.ZERO)
                .feeRateBps(BigInteger.valueOf(100L))
                .side(Side.BUY)
                .signatureType(SignatureType.EOA)
                .build();
        return SignedOrder.of(order,
                "0x1111111111111111111111111111111111111111111111111111111111111111"
                        + "2222222222222222222222222222222222222222222222222222222222222222"
                        + "1b");
    }

    @Test
    void postOrderSerializesWrappedBodyAndSignsL2() {
        // 重点：order 字段内 salt 是数字，signatureType 是数字，uint256 全是 string，side="BUY"
        wm.stubFor(post(urlPathEqualTo("/order"))
                .withHeader(L2HeaderBuilder.POLY_API_KEY, equalTo(CREDS.apiKey()))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(matchingJsonPath("$.order.salt", equalTo("1001")))
                .withRequestBody(matchingJsonPath("$.order.side", equalTo("BUY")))
                .withRequestBody(matchingJsonPath("$.order.signatureType", equalTo("0")))
                .withRequestBody(matchingJsonPath("$.order.tokenId", equalTo("1234")))
                .withRequestBody(matchingJsonPath("$.order.makerAmount", equalTo("100000000")))
                .withRequestBody(matchingJsonPath("$.order.signature"))
                .withRequestBody(matchingJsonPath("$.owner", equalTo(CREDS.apiKey())))
                .withRequestBody(matchingJsonPath("$.orderType", equalTo("GTC")))
                .withRequestBody(matchingJsonPath("$.postOnly", equalTo("false")))
                .willReturn(okJson("""
                        {"success":true,"orderID":"abc","status":"live"}
                        """)));

        PostOrderResponse resp = api()
                .postOrder(MAKER, CREDS, 1L, signedOrder(), OrderType.GTC, false)
                .join();

        assertThat(resp.success()).isTrue();
        assertThat(resp.orderId()).isEqualTo("abc");
        assertThat(resp.status()).isEqualTo("live");
    }

    private SignedOrderV2 signedOrderV2() {
        OrderV2 order = OrderV2.builder()
                .salt(BigInteger.valueOf(479_249_096_354L))
                .maker(MAKER)
                .signer(MAKER)
                .tokenId(BigInteger.valueOf(1234L))
                .makerAmount(BigInteger.valueOf(100_000_000L))
                .takerAmount(BigInteger.valueOf(50_000_000L))
                .side(Side.BUY)
                .signatureType(SignatureType.EOA)
                .expiration(BigInteger.ZERO)
                .timestamp(BigInteger.valueOf(1_700_000_000_000L))
                .metadata("0x" + "00".repeat(32))
                .builder("0x" + "00".repeat(32))
                .build();
        return SignedOrderV2.of(order,
                "0x808bcc18d94bfe59daf619655ab83ff5701a82c0685255a7e3aeeeff7a7f8350"
                        + "1984f86f573ef685b749c11eeacc57ead0c148a58b5460ee4a1c45708a290c511c");
    }

    /**
     * V2 POST /order wire 黄金向量。预期 JSON 由 py-clob-client-v2 同款 fixture 生成
     * （/tmp/v2_wire_fixture.py）：
     * <pre>
     * {"order":{"salt":479249096354,"maker":"0xf39f...","signer":"0xf39f...",
     *           "tokenId":"1234","makerAmount":"100000000","takerAmount":"50000000",
     *           "side":"BUY","expiration":"0","signatureType":0,
     *           "timestamp":"1700000000000","metadata":"0x00..","builder":"0x00..",
     *           "signature":"0x808b..."},
     *  "owner":"...","orderType":"GTC","deferExec":false,"postOnly":false}
     * </pre>
     */
    @Test
    void postOrderV2SerializesV2WireFormat() {
        wm.stubFor(post(urlPathEqualTo("/order"))
                .withHeader(L2HeaderBuilder.POLY_API_KEY, equalTo(CREDS.apiKey()))
                .withHeader("Content-Type", equalTo("application/json"))
                // V2-specific 字段
                .withRequestBody(matchingJsonPath("$.order.timestamp", equalTo("1700000000000")))
                .withRequestBody(matchingJsonPath("$.order.metadata",
                        equalTo("0x0000000000000000000000000000000000000000000000000000000000000000")))
                .withRequestBody(matchingJsonPath("$.order.builder",
                        equalTo("0x0000000000000000000000000000000000000000000000000000000000000000")))
                .withRequestBody(matchingJsonPath("$.deferExec", equalTo("false")))
                // V2 必须不带 V1 字段
                .withRequestBody(notMatching("(?s).*\"taker\".*"))
                .withRequestBody(notMatching("(?s).*\"nonce\".*"))
                .withRequestBody(notMatching("(?s).*\"feeRateBps\".*"))
                // V1 字段位置依旧（共有字段）
                .withRequestBody(matchingJsonPath("$.order.salt", equalTo("479249096354")))
                .withRequestBody(matchingJsonPath("$.order.side", equalTo("BUY")))
                .withRequestBody(matchingJsonPath("$.order.expiration", equalTo("0")))
                .withRequestBody(matchingJsonPath("$.order.signatureType", equalTo("0")))
                .withRequestBody(matchingJsonPath("$.order.tokenId", equalTo("1234")))
                .withRequestBody(matchingJsonPath("$.order.makerAmount", equalTo("100000000")))
                .withRequestBody(matchingJsonPath("$.order.takerAmount", equalTo("50000000")))
                .withRequestBody(matchingJsonPath("$.order.signature"))
                .withRequestBody(matchingJsonPath("$.owner", equalTo(CREDS.apiKey())))
                .withRequestBody(matchingJsonPath("$.orderType", equalTo("GTC")))
                .withRequestBody(matchingJsonPath("$.postOnly", equalTo("false")))
                .willReturn(okJson("""
                        {"success":true,"orderID":"v2-abc","status":"live"}
                        """)));

        PostOrderResponse resp = api()
                .postOrderV2(MAKER, CREDS, 1L, signedOrderV2(), OrderType.GTC, false, false)
                .join();
        assertThat(resp.success()).isTrue();
        assertThat(resp.orderId()).isEqualTo("v2-abc");
    }

    @Test
    void postOrderV2RejectsPostOnlyOnMarketType() {
        assertThatThrownBy(() -> api()
                .postOrderV2(MAKER, CREDS, 1L, signedOrderV2(), OrderType.FOK, true, false)
                .join())
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void postOrderRejectsPostOnlyOnMarketType() {
        assertThatThrownBy(() -> api()
                .postOrder(MAKER, CREDS, 1L, signedOrder(), OrderType.FOK, true)
                .join())
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void postOrdersBatchSerializesAsArray() {
        wm.stubFor(post(urlPathEqualTo("/orders"))
                .withRequestBody(matchingJsonPath("$.length()", equalTo("2")))
                .withRequestBody(matchingJsonPath("$[0].orderType", equalTo("GTC")))
                .withRequestBody(matchingJsonPath("$[1].orderType", equalTo("FOK")))
                .withRequestBody(matchingJsonPath("$[1].postOnly", equalTo("false")))
                .willReturn(okJson("""
                        {"success":true,"orderHashes":["0xaa","0xbb"]}
                        """)));

        List<PostOrdersEntry> entries = List.of(
                PostOrdersEntry.of(signedOrder(), OrderType.GTC),
                PostOrdersEntry.of(signedOrder(), OrderType.FOK));

        PostOrderResponse resp = api().postOrders(MAKER, CREDS, 1L, entries).join();
        assertThat(resp.success()).isTrue();
        assertThat(resp.orderHashes()).containsExactly("0xaa", "0xbb");
    }

    @Test
    void postOrdersRejectsEmptyAndOversize() {
        assertThatThrownBy(() -> api().postOrders(MAKER, CREDS, 1L, List.of()).join())
                .hasCauseInstanceOf(IllegalArgumentException.class);

        PostOrdersEntry e = PostOrdersEntry.of(signedOrder(), OrderType.GTC);
        List<PostOrdersEntry> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < 16; i++) tooMany.add(e);
        assertThatThrownBy(() -> api().postOrders(MAKER, CREDS, 1L, tooMany).join())
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancelOrderSendsOrderIDBody() {
        wm.stubFor(delete(urlPathEqualTo("/order"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("{\"orderID\":\"abc\"}"))
                .willReturn(okJson("""
                        {"canceled":["abc"],"not_canceled":{}}
                        """)));

        CancelResponse resp = api().cancelOrder(MAKER, CREDS, 1L, "abc").join();
        assertThat(resp.canceled()).containsExactly("abc");
        assertThat(resp.isFullySuccessful()).isTrue();
    }

    @Test
    void cancelOrdersSendsArrayBody() {
        wm.stubFor(delete(urlPathEqualTo("/orders"))
                .withRequestBody(equalToJson("[\"a\",\"b\"]"))
                .willReturn(okJson("""
                        {"canceled":["a"],"not_canceled":{"b":"unknown order"}}
                        """)));

        CancelResponse resp = api().cancelOrders(MAKER, CREDS, 1L, List.of("a", "b")).join();
        assertThat(resp.canceled()).containsExactly("a");
        assertThat(resp.notCanceled()).containsEntry("b", "unknown order");
        assertThat(resp.isFullySuccessful()).isFalse();
    }

    @Test
    void cancelAllSendsEmptyDeleteWithoutContentType() {
        wm.stubFor(delete(urlPathEqualTo("/cancel-all"))
                .withHeader(L2HeaderBuilder.POLY_SIGNATURE, matching(".+"))
                .withHeader("Content-Type", absent())
                .willReturn(okJson("""
                        {"canceled":["x","y"],"not_canceled":{}}
                        """)));

        CancelResponse resp = api().cancelAll(MAKER, CREDS, 1L).join();
        assertThat(resp.canceled()).containsExactly("x", "y");
    }

    @Test
    void getOrderHitsDataOrderPath() {
        wm.stubFor(get(urlPathEqualTo("/data/order/abc"))
                .withHeader(L2HeaderBuilder.POLY_SIGNATURE, matching(".+"))
                .willReturn(okJson("""
                        {"id":"abc","status":"live","side":"BUY","price":"0.55",
                         "original_size":"100","size_matched":"0",
                         "maker_address":"0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                         "asset_id":"1234","market":"0xabc","order_type":"GTC",
                         "created_at":1700000000}
                        """)));

        OpenOrder o = api().getOrder(MAKER, CREDS, 1L, "abc").join();
        assertThat(o.id()).isEqualTo("abc");
        assertThat(o.side()).isEqualTo(Side.BUY);
        assertThat(o.price()).isEqualByComparingTo("0.55");
        assertThat(o.orderType()).isEqualTo(OrderType.GTC);
    }

    @Test
    void cancelMarketOrdersByMarketSerializesMarketOnly() {
        // 只传 market 时 body = {"market":"0x..."}，asset_id 不应出现（NON_NULL 生效）
        Hash32 market = Hash32.fromHex(
                "0x1111111111111111111111111111111111111111111111111111111111111111");
        wm.stubFor(delete(urlPathEqualTo("/cancel-market-orders"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader(L2HeaderBuilder.POLY_SIGNATURE, matching(".+"))
                .withRequestBody(matchingJsonPath("$.market", equalTo(market.toHex())))
                .withRequestBody(notContaining("asset_id"))
                .willReturn(okJson("""
                        {"canceled":["a","b"],"not_canceled":{}}
                        """)));

        CancelResponse resp = api()
                .cancelMarketOrders(MAKER, CREDS, 1L, CancelMarketOrdersRequest.ofMarket(market))
                .join();
        assertThat(resp.canceled()).containsExactly("a", "b");
        assertThat(resp.isFullySuccessful()).isTrue();
    }

    @Test
    void cancelMarketOrdersByAssetIdSerializesAssetIdOnly() {
        BigInteger assetId = new BigInteger("777");
        wm.stubFor(delete(urlPathEqualTo("/cancel-market-orders"))
                .withRequestBody(matchingJsonPath("$.asset_id", equalTo("777")))
                .withRequestBody(notContaining("\"market\""))
                .willReturn(okJson("""
                        {"canceled":["z"],"not_canceled":{}}
                        """)));

        CancelResponse resp = api()
                .cancelMarketOrders(MAKER, CREDS, 1L, CancelMarketOrdersRequest.ofAssetId(assetId))
                .join();
        assertThat(resp.canceled()).containsExactly("z");
    }

    @Test
    void cancelMarketOrdersRejectsEmptyFilter() {
        assertThatThrownBy(() -> api()
                .cancelMarketOrders(MAKER, CREDS, 1L, new CancelMarketOrdersRequest(null, null))
                .join())
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isOrderScoringSignsPathOnlyAndForwardsQueryParam() {
        // HMAC 只覆盖 path=/order-scoring，不含 ?order_id=...；一次成功调用证明签名正确
        wm.stubFor(get(urlPathEqualTo("/order-scoring"))
                .withHeader(L2HeaderBuilder.POLY_SIGNATURE, matching(".+"))
                .withQueryParam("order_id", equalTo("abc"))
                .willReturn(okJson("{\"scoring\":true}")));

        OrderScoringResponse resp = api().isOrderScoring(MAKER, CREDS, 1L, "abc").join();
        assertThat(resp.scoring()).isTrue();
    }

    @Test
    void areOrdersScoringPostsIdArrayAndParsesMap() {
        wm.stubFor(post(urlPathEqualTo("/orders-scoring"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("[\"a\",\"b\"]"))
                .willReturn(okJson("""
                        {"a":true,"b":false}
                        """)));

        OrdersScoringResponse resp =
                api().areOrdersScoring(MAKER, CREDS, 1L, List.of("a", "b")).join();
        assertThat(resp.isScoring("a")).isTrue();
        assertThat(resp.isScoring("b")).isFalse();
        assertThat(resp.asMap()).hasSize(2);
    }

    @Test
    void areOrdersScoringRejectsEmptyList() {
        assertThatThrownBy(() -> api().areOrdersScoring(MAKER, CREDS, 1L, List.of()).join())
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getOpenOrdersPagesUntilTerminalCursor() {
        // Page 1: 有 next_cursor
        wm.stubFor(get(urlPathEqualTo("/data/orders"))
                .withQueryParam("next_cursor", absent())
                .withQueryParam("market", equalTo("0xabc"))
                .willReturn(okJson("""
                        {"data":[
                          {"id":"o1","status":"live","side":"BUY","price":"0.5",
                           "original_size":"10","size_matched":"0",
                           "maker_address":"0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                           "asset_id":"1","market":"0xabc","order_type":"GTC","created_at":1}
                        ],"next_cursor":"NEXT"}
                        """)));

        // Page 2: 终止
        wm.stubFor(get(urlPathEqualTo("/data/orders"))
                .withQueryParam("next_cursor", equalTo("NEXT"))
                .withQueryParam("market", equalTo("0xabc"))
                .willReturn(okJson("""
                        {"data":[
                          {"id":"o2","status":"live","side":"SELL","price":"0.6",
                           "original_size":"5","size_matched":"0",
                           "maker_address":"0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                           "asset_id":"2","market":"0xabc","order_type":"GTC","created_at":2}
                        ],"next_cursor":"LTE="}
                        """)));

        List<OpenOrder> all = api().getOpenOrders(MAKER, CREDS, 1L,
                        OpenOrderParams.builder().market("0xabc").build())
                .collect(Collectors.toList());

        assertThat(all).hasSize(2);
        assertThat(all.get(0).id()).isEqualTo("o1");
        assertThat(all.get(1).side()).isEqualTo(Side.SELL);
    }
}
