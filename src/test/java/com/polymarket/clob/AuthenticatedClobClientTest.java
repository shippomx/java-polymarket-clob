package com.polymarket.clob;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.auth.builder.BuilderConfig;
import com.polymarket.clob.exception.ClobAuthException;
import com.polymarket.clob.heartbeat.HeartbeatResponse;
import com.polymarket.clob.heartbeat.HeartbeatScheduler;
import com.polymarket.clob.http.SharedHttpClient;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.AssetType;
import com.polymarket.clob.model.BalanceAllowanceRequest;
import com.polymarket.clob.model.BalanceAllowanceResponse;
import com.polymarket.clob.model.ChainId;
import com.polymarket.clob.order.CancelMarketOrdersRequest;
import com.polymarket.clob.order.CancelResponse;
import com.polymarket.clob.order.OrderScoringResponse;
import com.polymarket.clob.order.OrdersScoringResponse;
import com.polymarket.clob.trade.Trade;
import com.polymarket.clob.trade.TradesRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedClobClientTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .failOnUnmatchedRequests(true)
            .build();

    private static final String PRIVATE_KEY =
            "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";

    private static final String CREDS_JSON = """
            {"apiKey":"00000000-0000-0000-0000-000000000000",
             "secret":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
             "passphrase":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
            """;

    private static final ApiCredentials CREDS = new ApiCredentials(
            "00000000-0000-0000-0000-000000000000",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

    private ClobClient newClient(long chainId) {
        return ClobClient.builder()
                .endpoint(wm.baseUrl() + "/")
                .chainId(chainId)
                .httpClient(SharedHttpClient.INSTANCE)
                .build();
    }

    @Test
    void authenticateWithCredentialsDerivesFunderForEoa() {
        try (ClobClient client = newClient(ChainId.POLYGON)) {
            Signer signer = LocalSigner.fromPrivateKey(PRIVATE_KEY);
            AuthenticatedClobClient authed = client.authenticate(signer, SignatureType.EOA, CREDS);
            assertThat(authed.funder()).isEqualTo(signer.address());
            assertThat(authed.callerAddress()).isEqualTo(signer.address());
            assertThat(authed.signatureType()).isEqualTo(SignatureType.EOA);
            assertThat(authed.apiKeyId()).isEqualTo(CREDS.apiKey());
        }
    }

    @Test
    void authenticateWithCredentialsDerivesProxyFunderOnPolygon() {
        try (ClobClient client = newClient(ChainId.POLYGON)) {
            Signer signer = LocalSigner.fromPrivateKey(PRIVATE_KEY);
            AuthenticatedClobClient authed = client.authenticate(
                    signer, SignatureType.POLY_PROXY, CREDS);
            assertThat(authed.funder()).isEqualTo(
                    Address.fromHex("0x365f0cA36ae1F641E02Fe3b7743673DA42A13a70"));
        }
    }

    @Test
    void authenticateRejectsUnsupportedCombo() {
        try (ClobClient client = newClient(ChainId.AMOY)) {
            Signer signer = LocalSigner.fromPrivateKey(PRIVATE_KEY);
            assertThatThrownBy(() -> client.authenticate(
                    signer, SignatureType.POLY_PROXY, CREDS))
                    .isInstanceOf(ClobAuthException.class)
                    .hasMessageContaining("funder");
        }
    }

    @Test
    void authenticateFetchesCredentialsWhenAbsent() {
        wm.stubFor(post(urlPathEqualTo("/auth/api-key"))
                .willReturn(okJson(CREDS_JSON)));

        try (ClobClient client = newClient(ChainId.AMOY)) {
            Signer signer = LocalSigner.fromPrivateKey(PRIVATE_KEY);
            AuthenticatedClobClient authed = client.authenticate(
                    signer, SignatureType.EOA, BigInteger.ZERO).join();
            assertThat(authed.apiKeyId()).isEqualTo(CREDS.apiKey());
        }
    }

    @Test
    void balanceAllowanceConvenienceFillsSignatureType() {
        wm.stubFor(get(urlPathEqualTo("/balance-allowance"))
                .withQueryParam("asset_type", equalTo("COLLATERAL"))
                .withQueryParam("signature_type", equalTo("1"))
                .willReturn(okJson("{\"balance\":\"0\"}")));

        try (ClobClient client = newClient(ChainId.POLYGON)) {
            Signer signer = LocalSigner.fromPrivateKey(PRIVATE_KEY);
            AuthenticatedClobClient authed = client.authenticate(
                    signer, SignatureType.POLY_PROXY, CREDS);

            BalanceAllowanceRequest req = BalanceAllowanceRequest.builder()
                    .assetType(AssetType.COLLATERAL)
                    .build();
            BalanceAllowanceResponse resp = authed.balanceAllowance(req).join();
            assertThat(resp).isNotNull();
        }
    }

    // ============================================================
    //  下面：Plan 3.5 / Plan 5 新增 API 的 wiring 测试
    //  目的并非"再测一遍 *ApiImpl 的细节"——它们已有专门的 wiremock 测试——
    //  而是验证 AuthenticatedClobClient 的便利包装方法把签名/凭证/timestamp
    //  正确地"接到底层"，不漏字段、不发错路径。
    // ============================================================

    private AuthenticatedClobClient authedEoa(ClobClient client) {
        return client.authenticate(
                LocalSigner.fromPrivateKey(PRIVATE_KEY), SignatureType.EOA, CREDS);
    }

    @Test
    void cancelMarketOrdersForwardsAssetIdToWire() {
        wm.stubFor(delete(urlPathEqualTo("/cancel-market-orders"))
                .withRequestBody(matchingJsonPath("$.asset_id", equalTo("42")))
                .withRequestBody(notContaining("\"market\""))
                .willReturn(okJson("""
                        {"canceled":["x"],"not_canceled":{}}""")));

        try (ClobClient client = newClient(ChainId.POLYGON)) {
            CancelResponse resp = authedEoa(client)
                    .cancelMarketOrders(CancelMarketOrdersRequest.ofAssetId(new BigInteger("42")))
                    .join();
            assertThat(resp.canceled()).containsExactly("x");
            assertThat(resp.isFullySuccessful()).isTrue();
        }
    }

    @Test
    void isOrderScoringExtractsScalarBoolean() {
        wm.stubFor(get(urlPathEqualTo("/order-scoring"))
                .withQueryParam("order_id", equalTo("abc"))
                .willReturn(okJson("{\"scoring\":true}")));

        try (ClobClient client = newClient(ChainId.POLYGON)) {
            OrderScoringResponse resp = authedEoa(client).isOrderScoring("abc").join();
            assertThat(resp.scoring()).isTrue();
        }
    }

    @Test
    void areOrdersScoringParsesMapResponse() {
        // wire 实际是裸 JSON 数组，对齐 Rust `serde_json::to_value(&order_ids)`
        wm.stubFor(post(urlPathEqualTo("/orders-scoring"))
                .withRequestBody(equalToJson("[\"a\",\"b\"]"))
                .willReturn(okJson("{\"a\":true,\"b\":false}")));

        try (ClobClient client = newClient(ChainId.POLYGON)) {
            OrdersScoringResponse resp = authedEoa(client)
                    .areOrdersScoring(List.of("a", "b")).join();
            assertThat(resp.size()).isEqualTo(2);
            assertThat(resp.isScoring("a")).isTrue();
            assertThat(resp.isScoring("b")).isFalse();
            assertThat(resp.asMap()).containsEntry("a", true).containsEntry("b", false);
            assertThat(resp.toString()).contains("a=true");
        }
    }

    @Test
    void getTradesEmitsPaginatedStream() {
        wm.stubFor(get(urlPathEqualTo("/data/trades"))
                .withQueryParam("next_cursor", absent())
                .willReturn(okJson("""
                        {"data":[],"next_cursor":"LTE="}""")));

        try (ClobClient client = newClient(ChainId.POLYGON)) {
            List<Trade> trades = authedEoa(client).getTrades(TradesRequest.none()).toList();
            assertThat(trades).isEmpty();
        }
    }

    @Test
    void postHeartbeatChainsId() {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        wm.stubFor(post(urlPathEqualTo("/v1/heartbeats"))
                .willReturn(okJson("{\"heartbeat_id\":\"" + id + "\"}")));

        try (ClobClient client = newClient(ChainId.POLYGON)) {
            HeartbeatResponse first = authedEoa(client).postHeartbeat(null).join();
            assertThat(first.heartbeatId()).isEqualTo(id);
            HeartbeatResponse next = authedEoa(client).postHeartbeat(id).join();
            assertThat(next.heartbeatId()).isEqualTo(id);
        }
    }

    @Test
    void startHeartbeatsTicksAndStopsCleanly() throws Exception {
        UUID id = UUID.fromString("22222222-2222-2222-2222-222222222222");
        wm.stubFor(post(urlPathEqualTo("/v1/heartbeats"))
                .willReturn(okJson("{\"heartbeat_id\":\"" + id + "\"}")));

        try (ClobClient client = newClient(ChainId.POLYGON)) {
            AuthenticatedClobClient authed = authedEoa(client);
            HeartbeatScheduler scheduler = authed.startHeartbeats(Duration.ofMillis(100));
            assertThat(scheduler.isActive()).isTrue();
            assertThat(scheduler.interval()).isEqualTo(Duration.ofMillis(100));

            // 等待最多 2s，至少观察到 1 次心跳完成（lastHeartbeatId != null）
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (scheduler.lastHeartbeatId() == null && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(scheduler.lastHeartbeatId()).isEqualTo(id);

            scheduler.close();
            assertThat(scheduler.isActive()).isFalse();
        }
    }

    @Test
    void promoteToBuilderProducesBuilderClientShareCallerAddress() {
        try (ClobClient client = newClient(ChainId.POLYGON)) {
            AuthenticatedClobClient authed = authedEoa(client);
            BuilderClobClient promoted = authed.promoteToBuilder(
                    BuilderConfig.local(CREDS));      // 复用 L2 凭证测 wiring 即可
            assertThat(promoted.callerAddress()).isEqualTo(authed.callerAddress());
            assertThat(promoted.authenticated()).isSameAs(authed);
            assertThat(promoted.builderConfig()).isInstanceOf(BuilderConfig.Local.class);
        }
    }

    @Test
    void promoteToBuilderRejectsNullConfig() {
        try (ClobClient client = newClient(ChainId.POLYGON)) {
            AuthenticatedClobClient authed = authedEoa(client);
            assertThatThrownBy(() -> authed.promoteToBuilder(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("config");
        }
    }
}
