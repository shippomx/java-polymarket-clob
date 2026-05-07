package com.polymarket.clob.gamma;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.concurrent.ExecutionException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class GammaClientProfileTest {

    private static final Address EOA = Address.fromHex("0x4cAfCf2D9A032f57088872f6546Bb68305d209D7");
    private static final Address WALLET = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");

    private WireMockServer server;
    private GammaClient client;
    private GammaSession session;

    @BeforeEach
    void start() {
        server = new WireMockServer(0);
        server.start();
        client = new GammaClient(URI.create(server.baseUrl()), HttpClient.newHttpClient());
        session = new GammaSession("polymarket_auth=val", Instant.now().plusSeconds(3600));
    }

    @AfterEach
    void stop() { server.stop(); }

    @Test
    void profileExistsTrueOn200() throws ExecutionException, InterruptedException {
        server.stubFor(get(urlPathEqualTo("/users"))
                .withQueryParam("address", equalTo(EOA.toLowerHex()))
                .willReturn(okJson("[{\"id\":\"u1\"}]")));
        assertThat(client.profileExists(session, EOA).get()).isTrue();
    }

    @Test
    void profileExistsFalseOn404() throws ExecutionException, InterruptedException {
        server.stubFor(get(urlPathEqualTo("/users"))
                .willReturn(notFound()));
        assertThat(client.profileExists(session, EOA).get()).isFalse();
    }

    @Test
    void profileExistsFalseOn200EmptyArray() throws ExecutionException, InterruptedException {
        server.stubFor(get(urlPathEqualTo("/users"))
                .willReturn(okJson("[]")));
        assertThat(client.profileExists(session, EOA).get()).isFalse();
    }

    @Test
    void createProfilePostsExpectedBody() throws ExecutionException, InterruptedException {
        server.stubFor(post(urlEqualTo("/profiles"))
                .withHeader("Cookie", equalTo("polymarket_auth=val"))
                .withRequestBody(matchingJsonPath("$.proxyWallet", equalTo(WALLET.toHex())))
                .withRequestBody(matchingJsonPath("$.users[0].address", equalTo(EOA.toHex())))
                .withRequestBody(matchingJsonPath("$.users[0].provider", equalTo("metamask")))
                .willReturn(aResponse().withStatus(201).withBody("{\"id\":\"p1\"}")));

        client.createProfile(session, EOA, WALLET).get();
        // 没异常即通过
    }

    @Test
    void ensureProfileSkipsWhenExists() throws ExecutionException, InterruptedException {
        server.stubFor(get(urlPathEqualTo("/users")).willReturn(okJson("[{\"id\":\"u1\"}]")));
        // 不 stub /profiles，如果意外被调用 WireMock 返回 404，会触发异常

        client.ensureProfile(session, EOA, WALLET).get();
        server.verify(0, postRequestedFor(urlEqualTo("/profiles")));
    }

    @Test
    void ensureProfileCreatesWhenMissing() throws ExecutionException, InterruptedException {
        server.stubFor(get(urlPathEqualTo("/users")).willReturn(notFound()));
        server.stubFor(post(urlEqualTo("/profiles"))
                .willReturn(aResponse().withStatus(201).withBody("{}")));

        client.ensureProfile(session, EOA, WALLET).get();
        server.verify(1, postRequestedFor(urlEqualTo("/profiles")));
    }
}
