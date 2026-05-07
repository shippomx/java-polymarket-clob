package com.polymarket.clob.funxyz;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FunxyzClientTest {

    /** HAR 抓的固定 EOA。 */
    private static final Address EOA = Address.fromHex("0x5f0fE47194FAC5FdE131C58359b614F520Db1342");
    /** HAR 抓的 recipient（Deposit Wallet）。 */
    private static final Address RECIPIENT = Address.fromHex("0xB51b3627E851EdeaFD81792F012C066805b6dFdE");

    private static final String OK_BODY = """
            {"depositAddr":"0x4C741213d8519429002ab3E69DE9620fb9b48C69",
             "solanaAddr":"CobugN8o4CnNVYL9jj7NGRDrdG2hJxAwMiPNZug7N6Pk",
             "tronAddr":"TN4Vfn2wjVZGM8z8oy8MFLSwcW36bs3418",
             "btcAddrSegwit":"bc1q7hum6lx3rad7xzsfjxrle4ryk6ev527pk0xzws",
             "blocked":false}
            """;

    private WireMockServer server;
    private FunxyzClient client;

    @BeforeEach
    void start() {
        server = new WireMockServer(0);
        server.start();
        client = new FunxyzClient(FunxyzConfig.builder()
                .baseUrl(URI.create(server.baseUrl()))
                .apiKey("test-key-123")
                .build());
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void happyPathReturnsAllFourAddresses() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/eoa")).willReturn(okJson(OK_BODY)));

        DepositAddresses addrs = client.getDepositAddresses(EOA, RECIPIENT).get();

        assertThat(addrs.evm())
                .isEqualTo(Address.fromHex("0x4C741213d8519429002ab3E69DE9620fb9b48C69"));
        assertThat(addrs.solana()).isEqualTo("CobugN8o4CnNVYL9jj7NGRDrdG2hJxAwMiPNZug7N6Pk");
        assertThat(addrs.tron()).isEqualTo("TN4Vfn2wjVZGM8z8oy8MFLSwcW36bs3418");
        assertThat(addrs.btcSegwit()).isEqualTo("bc1q7hum6lx3rad7xzsfjxrle4ryk6ev527pk0xzws");
    }

    @Test
    void requestBodyHasRequiredFields() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/eoa")).willReturn(okJson(OK_BODY)));

        client.getDepositAddresses(EOA, RECIPIENT).get();

        // 必填字段全到位
        server.verify(postRequestedFor(urlEqualTo("/v1/eoa"))
                .withRequestBody(matchingJsonPath("$.userId",
                        equalTo("0x5f0fe47194fac5fde131c58359b614f520db1342")))
                .withRequestBody(matchingJsonPath("$.recipientAddr",
                        equalTo("0xb51b3627e851edeafd81792f012c066805b6dfde")))
                .withRequestBody(matchingJsonPath("$.toChainId", equalTo("137")))
                .withRequestBody(matchingJsonPath("$.toTokenAddress",
                        equalTo("0x2791bca1f2de4661ed88a30c99a7a9449aa84174")))
                .withRequestBody(matchingJsonPath("$.clientMetadata.id"))
                .withRequestBody(matchingJsonPath(
                        "$.clientMetadata.selectedPaymentMethodInfo.paymentMethod",
                        equalTo("token_transfer"))));
    }

    @Test
    void requestHeadersSetCorrectly() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/eoa")).willReturn(okJson(OK_BODY)));

        client.getDepositAddresses(EOA, RECIPIENT).get();

        server.verify(postRequestedFor(urlEqualTo("/v1/eoa"))
                .withHeader("content-type", containing("application/json"))
                .withHeader("origin", equalTo("https://polymarket.com"))
                .withHeader("referer", equalTo("https://polymarket.com/"))
                .withHeader("x-api-key", equalTo("test-key-123")));
    }

    @Test
    void blockedTrueThrowsBlockedException() {
        String blockedBody = """
                {"depositAddr":"0x4C741213d8519429002ab3E69DE9620fb9b48C69",
                 "solanaAddr":"","tronAddr":"","btcAddrSegwit":"",
                 "blocked":true}
                """;
        server.stubFor(post(urlEqualTo("/v1/eoa")).willReturn(okJson(blockedBody)));

        assertThatThrownBy(() -> client.getDepositAddresses(EOA, RECIPIENT).get())
                .isInstanceOf(java.util.concurrent.ExecutionException.class)
                .hasCauseInstanceOf(FunxyzException.class)
                .satisfies(t -> {
                    FunxyzException fe = (FunxyzException) t.getCause();
                    assertThat(fe.isBlocked()).isTrue();
                    assertThat(fe.httpStatus()).isEqualTo(200);
                });
    }
}
