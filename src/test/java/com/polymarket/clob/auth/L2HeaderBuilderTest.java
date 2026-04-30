package com.polymarket.clob.auth;

import com.polymarket.clob.exception.ClobAuthException;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class L2HeaderBuilderTest {

    private static final Address EOA =
            Address.fromHex("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266");
    private static final ApiCredentials CREDS = new ApiCredentials(
            "00000000-0000-0000-0000-000000000000",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

    /**
     * Rust fixture (rs-clob-client/src/auth.rs l2_headers_should_succeed):
     *   GET http://localhost/, timestamp=1, body empty
     *   => POLY_SIGNATURE = "eHaylCwqRSOa2LFD77Nt_SaTpbsxzN8eTEI3LryhEj4="
     */
    @Test
    void headerSetMatchesRustFixture() {
        Map<String, String> h = L2HeaderBuilder.build(EOA, CREDS, "GET", "/", "", 1L);
        assertThat(h)
                .containsEntry(L2HeaderBuilder.POLY_ADDRESS,
                        "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266")
                .containsEntry(L2HeaderBuilder.POLY_API_KEY,
                        "00000000-0000-0000-0000-000000000000")
                .containsEntry(L2HeaderBuilder.POLY_PASSPHRASE,
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .containsEntry(L2HeaderBuilder.POLY_SIGNATURE,
                        "eHaylCwqRSOa2LFD77Nt_SaTpbsxzN8eTEI3LryhEj4=")
                .containsEntry(L2HeaderBuilder.POLY_TIMESTAMP, "1");
    }

    @Test
    void headerOrderIsStable() {
        Map<String, String> h = L2HeaderBuilder.build(EOA, CREDS, "GET", "/", null, 1L);
        assertThat(h.keySet()).containsExactly(
                L2HeaderBuilder.POLY_ADDRESS,
                L2HeaderBuilder.POLY_API_KEY,
                L2HeaderBuilder.POLY_PASSPHRASE,
                L2HeaderBuilder.POLY_SIGNATURE,
                L2HeaderBuilder.POLY_TIMESTAMP);
    }

    @Test
    void bodyAffectsSignature() {
        String a = L2HeaderBuilder.build(EOA, CREDS, "POST", "/auth/api-key", "", 1L)
                .get(L2HeaderBuilder.POLY_SIGNATURE);
        String b = L2HeaderBuilder.build(EOA, CREDS, "POST", "/auth/api-key", "{}", 1L)
                .get(L2HeaderBuilder.POLY_SIGNATURE);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void singleQuotesInBodyReplacedWithDouble() {
        String withSingle = L2HeaderBuilder.build(EOA, CREDS, "POST", "/p", "{'k':'v'}", 1L)
                .get(L2HeaderBuilder.POLY_SIGNATURE);
        String withDouble = L2HeaderBuilder.build(EOA, CREDS, "POST", "/p", "{\"k\":\"v\"}", 1L)
                .get(L2HeaderBuilder.POLY_SIGNATURE);
        assertThat(withSingle).isEqualTo(withDouble);
    }

    @Test
    void invalidSecretRaisesAuthException() {
        ApiCredentials bad = new ApiCredentials("k", "not base64!!", "p");
        assertThatThrownBy(() -> L2HeaderBuilder.build(EOA, bad, "GET", "/", "", 1L))
                .isInstanceOf(ClobAuthException.class)
                .hasMessageContaining("URL-safe base64");
    }

    @Test
    void polyAddressIsLowercase() {
        Map<String, String> h = L2HeaderBuilder.build(EOA, CREDS, "GET", "/", "", 1L);
        assertThat(h.get(L2HeaderBuilder.POLY_ADDRESS))
                .isEqualTo("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266")
                .doesNotContain("F39F");
    }
}
