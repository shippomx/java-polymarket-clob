package com.polymarket.clob.api;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.auth.L1HeaderBuilder;
import com.polymarket.clob.auth.L2HeaderBuilder;
import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.exception.ClobApiException;
import com.polymarket.clob.http.HttpTransport;
import com.polymarket.clob.http.JsonCodec;
import com.polymarket.clob.http.SharedHttpClient;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.ApiKeysResponse;
import com.polymarket.clob.model.ChainId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletionException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthApiImplTest {

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

    private AuthApi api() {
        HttpTransport t = new HttpTransport(
                URI.create(wm.baseUrl() + "/"),
                SharedHttpClient.INSTANCE,
                JsonCodec.objectMapper(),
                Duration.ofSeconds(5));
        return new AuthApiImpl(t, ChainId.AMOY);
    }

    private Signer signer() {
        return LocalSigner.fromPrivateKey(PRIVATE_KEY);
    }

    @Test
    void createApiKeyIssuesL1SignedPost() {
        wm.stubFor(post(urlEqualTo("/auth/api-key"))
                .withHeader(L1HeaderBuilder.POLY_ADDRESS,
                        equalTo("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266"))
                .withHeader(L1HeaderBuilder.POLY_TIMESTAMP, equalTo("10000000"))
                .withHeader(L1HeaderBuilder.POLY_NONCE, equalTo("23"))
                .withHeader(L1HeaderBuilder.POLY_SIGNATURE, matching("0x[0-9a-f]{130}"))
                .willReturn(okJson(CREDS_JSON)));

        ApiCredentials got = api().createApiKey(
                signer(), ChainId.AMOY, 10_000_000L, BigInteger.valueOf(23)).join();
        assertThat(got).isEqualTo(CREDS);
    }

    @Test
    void deriveApiKeyIssuesL1SignedGet() {
        wm.stubFor(get(urlPathEqualTo("/auth/derive-api-key"))
                .willReturn(okJson(CREDS_JSON)));

        ApiCredentials got = api().deriveApiKey(
                signer(), ChainId.POLYGON, 1L, BigInteger.ZERO).join();
        assertThat(got.apiKey()).isEqualTo("00000000-0000-0000-0000-000000000000");
    }

    @Test
    void createOrDeriveFallsBackOn4xx() {
        wm.stubFor(post(urlEqualTo("/auth/api-key"))
                .willReturn(aResponse().withStatus(400).withBody("{\"error\":\"already exists\"}")));
        wm.stubFor(get(urlPathEqualTo("/auth/derive-api-key"))
                .willReturn(okJson(CREDS_JSON)));

        ApiCredentials got = api().createOrDeriveApiKey(
                signer(), ChainId.POLYGON, 2L, BigInteger.ONE).join();
        assertThat(got).isEqualTo(CREDS);
    }

    @Test
    void createOrDeriveDoesNotRetryOn5xx() {
        wm.stubFor(post(urlEqualTo("/auth/api-key"))
                .willReturn(aResponse().withStatus(502)));

        assertThatThrownBy(() -> api().createOrDeriveApiKey(
                signer(), ChainId.POLYGON, 2L, BigInteger.ONE).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ClobApiException.class);
    }

    @Test
    void apiKeysIssuesL2SignedGet() {
        wm.stubFor(get(urlPathEqualTo("/auth/api-keys"))
                .withHeader(L2HeaderBuilder.POLY_API_KEY,
                        equalTo("00000000-0000-0000-0000-000000000000"))
                .withHeader(L2HeaderBuilder.POLY_ADDRESS,
                        equalTo("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266"))
                .willReturn(okJson("{\"apiKeys\":[\"a\",\"b\"]}")));

        Address caller = Address.fromHex("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266");
        ApiKeysResponse resp = api().apiKeys(caller, CREDS, 1L).join();
        assertThat(resp.apiKeys()).containsExactly("a", "b");
    }

    @Test
    void apiKeysHandlesNullList() {
        wm.stubFor(get(urlPathEqualTo("/auth/api-keys"))
                .willReturn(okJson("{\"apiKeys\":null}")));

        Address caller = Address.fromHex("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266");
        ApiKeysResponse resp = api().apiKeys(caller, CREDS, 1L).join();
        assertThat(resp.apiKeys()).isEmpty();
    }

    @Test
    void deleteApiKeyIssuesL2SignedDelete() {
        wm.stubFor(delete(urlPathEqualTo("/auth/api-key"))
                .withHeader(L2HeaderBuilder.POLY_API_KEY, equalTo(CREDS.apiKey()))
                .willReturn(okJson("{\"deleted\":true}")));

        Address caller = Address.fromHex("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266");
        assertThat(api().deleteApiKey(caller, CREDS, 1L).join().get("deleted").asBoolean())
                .isTrue();
    }
}
