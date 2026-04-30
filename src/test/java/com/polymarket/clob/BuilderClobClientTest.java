package com.polymarket.clob;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.auth.L2HeaderBuilder;
import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.auth.builder.BuilderConfig;
import com.polymarket.clob.auth.builder.BuilderHeaderBuilder;
import com.polymarket.clob.http.SharedHttpClient;
import com.polymarket.clob.model.ChainId;
import com.polymarket.clob.order.CancelMarketOrdersRequest;

import java.math.BigInteger;
import com.polymarket.clob.trade.BuilderTrade;
import com.polymarket.clob.trade.Trade;
import com.polymarket.clob.trade.TradesRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BuilderClobClient} 的"桥接层"集成测试。
 *
 * <p>目标是覆盖 typestate 升级后的 Builder 专属路径：
 * <ul>
 *   <li>升级后依旧能调用 L2 能力（透传 delegate）；</li>
 *   <li>Builder 专属端点自动叠加 {@code POLY_BUILDER_*} 四联头；</li>
 *   <li>{@code createBuilderApiKey} 走普通 L2，不带 Builder 头；</li>
 *   <li>{@code revokeBuilderApiKey} / {@code listBuilderApiKeys} / {@code getBuilderTrades}
 *       都带 Builder 头。</li>
 * </ul>
 *
 * <p>这里不单独测 Builder 头的 HMAC 正确性——{@code BuilderHeaderBuilderTest} 已做过；
 * 本类只验证 Builder 头是否被"带到出站请求"上。</p>
 */
class BuilderClobClientTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .failOnUnmatchedRequests(true)
            .build();

    private static final String PRIVATE_KEY =
            "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";

    private static final ApiCredentials L2_CREDS = new ApiCredentials(
            "00000000-0000-0000-0000-000000000000",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

    private static final ApiCredentials BUILDER_CREDS = new ApiCredentials(
            "11111111-1111-1111-1111-111111111111",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

    private BuilderClobClient builderClient() {
        Signer signer = LocalSigner.fromPrivateKey(PRIVATE_KEY);
        ClobClient base = ClobClient.builder()
                .endpoint(wm.baseUrl() + "/")
                .chainId(ChainId.POLYGON)
                .httpClient(SharedHttpClient.INSTANCE)
                .build();
        AuthenticatedClobClient authed = base.authenticate(signer, SignatureType.EOA, L2_CREDS);
        return authed.promoteToBuilder(BuilderConfig.local(BUILDER_CREDS));
    }

    @Test
    void promoteExposesBuilderConfigAndUnderlyingAuthenticated() {
        BuilderClobClient b = builderClient();
        assertThat(b.builderConfig()).isInstanceOf(BuilderConfig.Local.class);
        assertThat(b.authenticated()).isNotNull();
        assertThat(b.callerAddress()).isEqualTo(b.authenticated().callerAddress());
        assertThat(b.chainId()).isEqualTo(ChainId.POLYGON);
        assertThat(b.headerBuilder()).isNotNull();
        // SignatureType / apiKeyId / funder 都透传
        assertThat(b.signatureType()).isEqualTo(SignatureType.EOA);
        assertThat(b.apiKeyId()).isEqualTo(L2_CREDS.apiKey());
        // API 对象透传，与底层完全同引用
        assertThat(b.trade()).isSameAs(b.authenticated().trade());
        assertThat(b.heartbeat()).isSameAs(b.authenticated().heartbeat());
        assertThat(b.builder()).isSameAs(b.authenticated().builder());
    }

    @Test
    void getBuilderTradesSendsBothL2AndBuilderHeaders() {
        wm.stubFor(get(urlPathEqualTo("/builder/trades"))
                .withHeader(L2HeaderBuilder.POLY_SIGNATURE, matching(".+"))
                .withHeader(L2HeaderBuilder.POLY_API_KEY, equalTo(L2_CREDS.apiKey()))
                .withHeader(BuilderHeaderBuilder.POLY_BUILDER_API_KEY, equalTo(BUILDER_CREDS.apiKey()))
                .withHeader(BuilderHeaderBuilder.POLY_BUILDER_PASSPHRASE, equalTo(BUILDER_CREDS.passphrase()))
                .withHeader(BuilderHeaderBuilder.POLY_BUILDER_SIGNATURE, matching(".+"))
                .withHeader(BuilderHeaderBuilder.POLY_BUILDER_TIMESTAMP, matching("[0-9]+"))
                .willReturn(okJson("""
                        {"data":[
                          {"id":"b1","tradeType":"LIMIT_MATCHED","side":"BUY",
                           "size":"1","price":"0.5","status":"MATCHED",
                           "matchTime":1,"lastUpdate":1,"bucketIndex":0,
                           "takerOrderId":"o","outcome":"YES","assetId":"1","asset":"1",
                           "trader":"0x0",
                           "market":"0x0000000000000000000000000000000000000000000000000000000000000000",
                           "feeRateBps":"0","makerOrders":[],
                           "takerOrderHash":"0x0000000000000000000000000000000000000000000000000000000000000000",
                           "transactionHash":"0x0000000000000000000000000000000000000000000000000000000000000000",
                           "traderSide":"TAKER"}
                        ],"next_cursor":"LTE="}
                        """)));

        List<BuilderTrade> trades = builderClient()
                .getBuilderTrades(TradesRequest.none())
                .toList();

        assertThat(trades).hasSize(1);
        assertThat(trades.get(0).getId()).isEqualTo("b1");
    }

    @Test
    void listBuilderApiKeysAttachesBuilderHeaders() {
        wm.stubFor(get(urlPathEqualTo("/auth/builder-api-key"))
                .withHeader(L2HeaderBuilder.POLY_SIGNATURE, matching(".+"))
                .withHeader(BuilderHeaderBuilder.POLY_BUILDER_API_KEY, equalTo(BUILDER_CREDS.apiKey()))
                .willReturn(okJson("""
                        [{"key":"11111111-1111-1111-1111-111111111111",
                          "created_at":"2024-01-01T00:00:00Z","revoked_at":null}]
                        """)));

        var list = builderClient().listBuilderApiKeys().join();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).isActive()).isTrue();
    }

    @Test
    void revokeBuilderApiKeyAttachesBuilderHeaders() {
        wm.stubFor(delete(urlPathEqualTo("/auth/builder-api-key"))
                .withHeader(L2HeaderBuilder.POLY_SIGNATURE, matching(".+"))
                .withHeader(BuilderHeaderBuilder.POLY_BUILDER_API_KEY, equalTo(BUILDER_CREDS.apiKey()))
                .willReturn(okJson("{}")));

        builderClient().revokeBuilderApiKey().join();
        wm.verify(deleteRequestedFor(urlPathEqualTo("/auth/builder-api-key"))
                .withHeader(BuilderHeaderBuilder.POLY_BUILDER_SIGNATURE, matching(".+")));
    }

    @Test
    void createBuilderApiKeyUsesL2OnlyWithoutBuilderHeaders() {
        wm.stubFor(post(urlPathEqualTo("/auth/builder-api-key"))
                .withHeader(L2HeaderBuilder.POLY_SIGNATURE, matching(".+"))
                // 明确：create 不应带 Builder 头（发起人还未持有 Builder 凭证）
                .withHeader(BuilderHeaderBuilder.POLY_BUILDER_API_KEY, absent())
                .withHeader(BuilderHeaderBuilder.POLY_BUILDER_SIGNATURE, absent())
                .willReturn(okJson("""
                        {"apiKey":"22222222-2222-2222-2222-222222222222",
                         "secret":"CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCA=",
                         "passphrase":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}
                        """)));

        ApiCredentials fresh = builderClient().createBuilderApiKey().join();
        assertThat(fresh.apiKey()).isEqualTo("22222222-2222-2222-2222-222222222222");
        wm.verify(postRequestedFor(urlPathEqualTo("/auth/builder-api-key")));
    }

    @Test
    void delegatesCancelMarketOrdersToUnderlyingAuthenticatedClient() {
        wm.stubFor(delete(urlPathEqualTo("/cancel-market-orders"))
                .withHeader(L2HeaderBuilder.POLY_SIGNATURE, matching(".+"))
                // Builder 头不应渗透到非 Builder 专属端点（透传调用等同普通认证态）
                .withHeader(BuilderHeaderBuilder.POLY_BUILDER_API_KEY, absent())
                .willReturn(okJson("""
                        {"canceled":["id1","id2"],"not_canceled":{}}""")));

        var resp = builderClient().cancelMarketOrders(
                CancelMarketOrdersRequest.ofAssetId(new BigInteger("42"))).join();
        assertThat(resp.canceled()).containsExactly("id1", "id2");
    }

    @Test
    void delegatesGetTradesToUnderlyingAuthenticatedClient() {
        wm.stubFor(get(urlPathEqualTo("/data/trades"))
                .withQueryParam("next_cursor", absent())
                .withHeader(L2HeaderBuilder.POLY_SIGNATURE, matching(".+"))
                .withHeader(BuilderHeaderBuilder.POLY_BUILDER_API_KEY, absent())
                .willReturn(okJson("""
                        {"data":[],"next_cursor":"LTE="}""")));

        List<Trade> trades = builderClient().getTrades(TradesRequest.none()).toList();
        assertThat(trades).isEmpty();
        wm.verify(getRequestedFor(urlPathEqualTo("/data/trades")));
    }
}
