package com.polymarket.clob.api;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.auth.L2HeaderBuilder;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.http.HttpTransport;
import com.polymarket.clob.http.JsonCodec;
import com.polymarket.clob.http.SharedHttpClient;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.AssetType;
import com.polymarket.clob.model.BalanceAllowanceRequest;
import com.polymarket.clob.model.BalanceAllowanceResponse;
import com.polymarket.clob.model.BanStatusResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class AccountApiImplTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .failOnUnmatchedRequests(true)
            .build();

    private static final ApiCredentials CREDS = new ApiCredentials(
            "00000000-0000-0000-0000-000000000000",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    private static final Address CALLER =
            Address.fromHex("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266");

    private AccountApi api() {
        HttpTransport t = new HttpTransport(
                URI.create(wm.baseUrl() + "/"),
                SharedHttpClient.INSTANCE,
                JsonCodec.objectMapper(),
                Duration.ofSeconds(5));
        return new AccountApiImpl(t);
    }

    @Test
    void balanceAllowanceSendsCollateralQueryAndL2Headers() {
        wm.stubFor(get(urlPathEqualTo("/balance-allowance"))
                .withQueryParam("asset_type", equalTo("COLLATERAL"))
                .withQueryParam("token_id", equalTo("1"))
                .withQueryParam("signature_type", equalTo("0"))
                .withHeader(L2HeaderBuilder.POLY_API_KEY, equalTo(CREDS.apiKey()))
                .withHeader(L2HeaderBuilder.POLY_ADDRESS,
                        equalTo("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266"))
                .willReturn(okJson("""
                        {"balance":"123.45",
                         "allowances":{"0x1111111111111111111111111111111111111111":"999"}}
                        """)));

        BalanceAllowanceRequest req = BalanceAllowanceRequest.builder()
                .assetType(AssetType.COLLATERAL)
                .tokenId(BigInteger.ONE)
                .signatureType(SignatureType.EOA)
                .build();
        BalanceAllowanceResponse resp = api().balanceAllowance(CALLER, CREDS, 1L, req).join();

        assertThat(resp.balance()).isEqualByComparingTo("123.45");
        assertThat(resp.allowances())
                .containsEntry(Address.fromHex("0x1111111111111111111111111111111111111111"), "999");
    }

    @Test
    void balanceAllowanceWorksWithoutOptionalFields() {
        wm.stubFor(get(urlPathEqualTo("/balance-allowance"))
                .withQueryParam("asset_type", equalTo("CONDITIONAL"))
                .willReturn(okJson("{\"balance\":\"0\"}")));

        BalanceAllowanceRequest req = BalanceAllowanceRequest.builder()
                .assetType(AssetType.CONDITIONAL)
                .build();
        BalanceAllowanceResponse resp = api().balanceAllowance(CALLER, CREDS, 1L, req).join();

        assertThat(resp.balance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(resp.allowances()).isEmpty();
    }

    @Test
    void closedOnlyModeReturnsFlag() {
        wm.stubFor(get(urlPathEqualTo("/auth/ban-status/closed-only"))
                .willReturn(okJson("{\"closed_only\":true}")));

        BanStatusResponse resp = api().closedOnlyMode(CALLER, CREDS, 1L).join();
        assertThat(resp.closedOnly()).isTrue();
    }

    @Test
    void withDefaultSignatureTypeFillsGap() {
        BalanceAllowanceRequest base = BalanceAllowanceRequest.builder()
                .assetType(AssetType.COLLATERAL)
                .build();
        BalanceAllowanceRequest filled = base.withDefaultSignatureType(SignatureType.POLY_PROXY);
        assertThat(filled.signatureType()).contains(SignatureType.POLY_PROXY);

        BalanceAllowanceRequest already = BalanceAllowanceRequest.builder()
                .assetType(AssetType.COLLATERAL)
                .signatureType(SignatureType.EOA)
                .build();
        assertThat(already.withDefaultSignatureType(SignatureType.POLY_GNOSIS_SAFE)
                .signatureType())
                .contains(SignatureType.EOA);
    }
}
