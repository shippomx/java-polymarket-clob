package com.polymarket.clob.deposit;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.gamma.GammaSession;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DepositWalletRelayerTest {

    private static final Address EOA    = Address.fromHex("0x4cAfCf2D9A032f57088872f6546Bb68305d209D7");
    private static final Address WALLET = Address.fromHex("0xADA4563a6738215C56d2B59BC1c5A1DB65B1fD00");

    private WireMockServer server;
    private DepositWalletRelayer relayer;

    @BeforeEach
    void start() {
        server = new WireMockServer(0);
        server.start();
        GammaSession session = new GammaSession("polymarket_auth=val", Instant.now().plusSeconds(3600));
        relayer = new DepositWalletRelayer(URI.create(server.baseUrl()), HttpClient.newHttpClient(), session);
    }

    @AfterEach
    void stop() { server.stop(); }

    @Test
    void submitWalletCreatePostsTypeFromTo() throws Exception {
        server.stubFor(post(urlEqualTo("/submit"))
                .withHeader("Cookie", equalTo("polymarket_auth=val"))
                .withRequestBody(matchingJsonPath("$.type", equalTo("WALLET-CREATE")))
                .withRequestBody(matchingJsonPath("$.from", equalTo(EOA.toLowerHex())))
                .withRequestBody(matchingJsonPath("$.to", equalTo(PolymarketContracts.FACTORY.toLowerHex())))
                .willReturn(okJson("{\"transactionID\":\"tx-1\",\"state\":\"STATE_NEW\"}")));

        String txId = relayer.submitWalletCreate(EOA, PolymarketContracts.FACTORY);

        assertThat(txId).isEqualTo("tx-1");
    }

    @Test
    void submitBatchPostsExpectedShape() throws Exception {
        server.stubFor(post(urlEqualTo("/submit"))
                .withRequestBody(matchingJsonPath("$.type", equalTo("WALLET")))
                .withRequestBody(matchingJsonPath("$.signature"))
                .withRequestBody(matchingJsonPath("$.depositWalletParams.depositWallet",
                        equalTo(WALLET.toLowerHex())))
                .withRequestBody(matchingJsonPath("$.depositWalletParams.deadline",
                        equalTo("1778081214")))
                .withRequestBody(matchingJsonPath("$.depositWalletParams.calls[0].target"))
                .willReturn(okJson("{\"transactionID\":\"tx-2\"}")));

        SignedBatch batch = new SignedBatch(EOA, PolymarketContracts.FACTORY, WALLET,
                BigInteger.ZERO, BigInteger.valueOf(1778081214L),
                List.of(new Call(PolymarketContracts.USDC_E, BigInteger.ZERO, new byte[]{0x01})),
                new byte[65]);

        assertThat(relayer.submitBatch(batch)).isEqualTo("tx-2");
    }

    @Test
    void getRelayerNonceParsesNumericResponse() throws Exception {
        server.stubFor(get(urlPathEqualTo("/nonce"))
                .withQueryParam("address", equalTo(EOA.toLowerHex()))
                .withQueryParam("type", equalTo("WALLET"))
                .willReturn(okJson("{\"nonce\":7}")));

        assertThat(relayer.getRelayerNonce(EOA)).isEqualTo(BigInteger.valueOf(7));
    }

    @Test
    void waitForTxPollsUntilConfirmed() throws Exception {
        server.stubFor(get(urlPathEqualTo("/transaction"))
                .inScenario("poll").whenScenarioStateIs("Started")
                .willReturn(okJson("[{\"state\":\"STATE_NEW\"}]"))
                .willSetStateTo("seen-once"));
        server.stubFor(get(urlPathEqualTo("/transaction"))
                .inScenario("poll").whenScenarioStateIs("seen-once")
                .willReturn(okJson("[{\"state\":\"STATE_CONFIRMED\",\"transactionHash\":\"0xabc\"}]")));

        RelayerTxResult r = relayer.waitForTx("tx-3", Duration.ofMillis(10), 10);
        assertThat(r.isConfirmed()).isTrue();
        assertThat(r.txHash()).isEqualTo("0xabc");
    }

    @Test
    void waitForTxFailedThrows() {
        server.stubFor(get(urlPathEqualTo("/transaction"))
                .willReturn(okJson("[{\"state\":\"STATE_FAILED\",\"errorMsg\":\"deadline too soon\"}]")));

        assertThatThrownBy(() -> relayer.waitForTx("tx-4", Duration.ofMillis(10), 10))
                .isInstanceOf(RelayerTxFailedException.class)
                .hasMessageContaining("deadline too soon");
    }

    @Test
    void submitNon2xxThrowsIOException() {
        server.stubFor(post(urlEqualTo("/submit")).willReturn(serverError().withBody("boom")));

        assertThatThrownBy(() -> relayer.submitWalletCreate(EOA, PolymarketContracts.FACTORY))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("500");
    }
}
