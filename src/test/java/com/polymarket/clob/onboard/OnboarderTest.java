package com.polymarket.clob.onboard;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.chain.EvmRpcClient;
import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.order.OrderType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class OnboarderTest {

    private static final String PK_HEX = "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";

    private WireMockServer gamma;
    private WireMockServer relayer;
    private WireMockServer clob;
    private Signer eoa;

    @BeforeEach
    void start() {
        gamma   = startMock();
        relayer = startMock();
        clob    = startMock();
        eoa = LocalSigner.fromPrivateKeyHex(PK_HEX);
    }

    @AfterEach
    void stop() { gamma.stop(); relayer.stop(); clob.stop(); }

    private static WireMockServer startMock() {
        WireMockServer s = new WireMockServer(0);
        s.start();
        return s;
    }

    private OnboardingConfig.Builder baseCfg() {
        return OnboardingConfig.builder()
                .gammaHost(URI.create(gamma.baseUrl()))
                .relayerHost(URI.create(relayer.baseUrl()))
                .clobHost(URI.create(clob.baseUrl()))
                .rpcUrl(URI.create("http://unused"))
                .relayerPollInterval(Duration.ofMillis(10))
                .relayerMaxAttempts(20);
    }

    private EvmRpcClient stubRpcAlreadyDeployed(Address wallet) {
        return new EvmRpcClient() {
            @Override public CompletableFuture<byte[]> ethCall(Address to, byte[] callData) {
                if (to.equals(PolymarketContracts.FACTORY)) {
                    byte[] out = new byte[32];
                    System.arraycopy(wallet.toBytes(), 0, out, 12, 20);
                    return CompletableFuture.completedFuture(out);
                }
                // 全部 max → 不需要 batch
                byte[] max = HexFormat.of().parseHex(
                        "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
                return CompletableFuture.completedFuture(max);
            }
            @Override public CompletableFuture<byte[]> getCode(Address addr) {
                return CompletableFuture.completedFuture(new byte[]{0x60, (byte) 0x80});
            }
        };
    }

    private void stubGammaLoginOk() {
        gamma.stubFor(get(urlEqualTo("/nonce")).willReturn(okJson("{\"nonce\":\"abc\"}")
                .withHeader("Set-Cookie", "polymarket_anon=anon; Path=/")));
        gamma.stubFor(get(urlEqualTo("/login")).willReturn(okJson("{}")
                .withHeader("Set-Cookie", "polymarket_auth=auth; Path=/")));
        gamma.stubFor(get(urlPathEqualTo("/users")).willReturn(okJson("[{\"id\":\"u\"}]")));
    }

    private void stubClobAuthOk() {
        // Adjust the URL path and response shape if AuthApi uses different endpoints
        clob.stubFor(get(urlEqualTo("/auth/derive-api-key"))
                .willReturn(okJson("{\"apiKey\":\"k\",\"secret\":\"c2VjcmV0\",\"passphrase\":\"p\"}")));
    }

    @Test
    void runWithExistingWalletAndAllAllowancesSetSkipsRelayer() throws ExecutionException, InterruptedException {
        Address wallet = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");

        stubGammaLoginOk();
        stubClobAuthOk();

        Onboarder onboarder = Onboarder.forTesting(baseCfg().build(), stubRpcAlreadyDeployed(wallet));
        OnboardingResult r = onboarder.run(eoa).get();

        assertThat(r.wallet()).isEqualTo(wallet);
        // creds derived
        relayer.verify(0, postRequestedFor(urlEqualTo("/submit")));
    }

    @Test
    void runWithNonexistentWalletDeploysAndApproves() throws ExecutionException, InterruptedException {
        Address wallet = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");

        stubGammaLoginOk();
        stubClobAuthOk();

        relayer.stubFor(post(urlEqualTo("/submit"))
                .willReturn(okJson("{\"transactionID\":\"tx\",\"state\":\"STATE_NEW\"}")));
        relayer.stubFor(get(urlPathEqualTo("/transaction"))
                .willReturn(okJson("[{\"state\":\"STATE_CONFIRMED\",\"transactionHash\":\"0xabc\"}]")));
        relayer.stubFor(get(urlPathEqualTo("/nonce")).willReturn(okJson("{\"nonce\":0}")));

        // RPC: 第 1 次 getCode 空（未部署），第 2+ 次 bytecode；allowance 全 0
        EvmRpcClient rpc = new EvmRpcClient() {
            int codeCalls = 0;
            @Override public CompletableFuture<byte[]> ethCall(Address to, byte[] callData) {
                if (to.equals(PolymarketContracts.FACTORY)) {
                    byte[] out = new byte[32];
                    System.arraycopy(wallet.toBytes(), 0, out, 12, 20);
                    return CompletableFuture.completedFuture(out);
                }
                return CompletableFuture.completedFuture(new byte[32]);
            }
            @Override public CompletableFuture<byte[]> getCode(Address addr) {
                codeCalls++;
                return CompletableFuture.completedFuture(codeCalls == 1 ? new byte[0] : new byte[]{0x60});
            }
        };

        Onboarder onboarder = Onboarder.forTesting(baseCfg().build(), rpc);
        OnboardingResult r = onboarder.run(eoa).get();

        assertThat(r.wallet()).isEqualTo(wallet);
        // 至少 2 次 submit（WALLET-CREATE + WALLET batch）
        relayer.verify(2, postRequestedFor(urlEqualTo("/submit")));
    }

    @Test
    void runWithTestOrderPlacesOrder() throws ExecutionException, InterruptedException {
        Address wallet = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");

        stubGammaLoginOk();
        stubClobAuthOk();

        // Stub POST /order endpoint
        clob.stubFor(post(urlPathEqualTo("/order"))
                .willReturn(okJson(
                        "{\"errorMsg\":\"\",\"orderID\":\"0xord\",\"status\":\"matched\",\"transactionsHashes\":[]}")));

        OnboardingConfig cfg = baseCfg()
                .testOrder(new TestOrderArgs(
                        new BigInteger("57597306756265660"),
                        Side.BUY,
                        new BigDecimal("0.10"),
                        new BigDecimal("5"),
                        OrderType.GTC,
                        "0.01",
                        false))
                .build();

        Onboarder onboarder = Onboarder.forTesting(cfg, stubRpcAlreadyDeployed(wallet));
        OnboardingResult r = onboarder.run(eoa).get();

        assertThat(r.testOrder()).isPresent();
        assertThat(r.testOrder().get().orderId()).isEqualTo("0xord");
        clob.verify(1, postRequestedFor(urlPathEqualTo("/order")));
    }
}
