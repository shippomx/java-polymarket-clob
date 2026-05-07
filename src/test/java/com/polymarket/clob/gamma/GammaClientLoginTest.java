package com.polymarket.clob.gamma;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.Signer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.concurrent.ExecutionException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GammaClientLoginTest {

    /** 固定测试私钥 → EOA 0xf39F...2266（Anvil account #0）。 */
    private static final String PK_HEX = "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";

    private WireMockServer server;
    private GammaClient client;
    private Signer signer;

    @BeforeEach
    void start() {
        server = new WireMockServer(0);
        server.start();
        client = new GammaClient(URI.create(server.baseUrl()), HttpClient.newHttpClient());
        signer = LocalSigner.fromPrivateKeyHex(PK_HEX);
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void loginIssuesNonceAndLoginAndMergesCookies() throws ExecutionException, InterruptedException {
        server.stubFor(get(urlEqualTo("/nonce"))
                .willReturn(okJson("{\"nonce\":\"abc123\"}")
                        .withHeader("Set-Cookie", "polymarket_anon=anon-val; Path=/")));

        server.stubFor(get(urlEqualTo("/login"))
                .withHeader("Authorization", matching("Bearer .+:::0x[0-9a-f]+"))
                .withHeader("Cookie", matching(".*polymarket_anon=anon-val.*"))
                .willReturn(okJson("{\"ok\":true}")
                        .withHeader("Set-Cookie", "polymarket_auth=auth-val; Path=/")));

        GammaSession session = client.loginWithSiwe(signer, 137).get();

        assertThat(session.cookieHeader())
                .contains("polymarket_anon=anon-val")
                .contains("polymarket_auth=auth-val");
    }

    @Test
    void loginRejectionThrowsAuthException() {
        server.stubFor(get(urlEqualTo("/nonce"))
                .willReturn(okJson("{\"nonce\":\"abc\"}")));
        server.stubFor(get(urlEqualTo("/login"))
                .willReturn(aResponse().withStatus(401).withBody("Unauthorized")));

        assertThatThrownBy(() -> client.loginWithSiwe(signer, 137).get())
                .hasCauseInstanceOf(GammaAuthException.class);
    }
}
