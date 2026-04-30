package com.polymarket.clob.auth;

import com.polymarket.clob.model.ChainId;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class L1HeaderBuilderTest {

    private static final String PRIVATE_KEY =
            "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";

    /**
     * Rust fixture (rs-clob-client/src/auth.rs l1_headers_should_succeed):
     *   chain=AMOY, timestamp=10_000_000, nonce=23
     *   signature = 0xf62319a9...1b
     *   address   = 0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266 (lowercase)
     */
    @Test
    void headerSetMatchesRustFixture() {
        LocalSigner s = LocalSigner.fromPrivateKey(PRIVATE_KEY);
        Map<String, String> h = L1HeaderBuilder.build(s, ChainId.AMOY, 10_000_000L, BigInteger.valueOf(23))
                .join();

        assertThat(h).containsEntry(L1HeaderBuilder.POLY_ADDRESS,
                "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266");
        assertThat(h).containsEntry(L1HeaderBuilder.POLY_NONCE, "23");
        assertThat(h).containsEntry(L1HeaderBuilder.POLY_TIMESTAMP, "10000000");
        assertThat(h).containsEntry(L1HeaderBuilder.POLY_SIGNATURE,
                "0xf62319a987514da40e57e2f4d7529f7bac38f0355bd88bb5adbb3768d80de6c1"
                        + "682518e0af677d5260366425f4361e7b70c25ae232aff0ab2331e2b164a1aedc"
                        + "1b");
    }

    @Test
    void nullNonceDefaultsToZero() {
        LocalSigner s = LocalSigner.fromPrivateKey(PRIVATE_KEY);
        Map<String, String> h = L1HeaderBuilder.build(s, ChainId.POLYGON, 1L, null).join();
        assertThat(h).containsEntry(L1HeaderBuilder.POLY_NONCE, "0");
    }

    @Test
    void headerOrderIsStable() {
        LocalSigner s = LocalSigner.fromPrivateKey(PRIVATE_KEY);
        Map<String, String> h = L1HeaderBuilder.build(s, ChainId.POLYGON, 1L, BigInteger.ZERO).join();
        assertThat(h.keySet()).containsExactly(
                L1HeaderBuilder.POLY_ADDRESS,
                L1HeaderBuilder.POLY_NONCE,
                L1HeaderBuilder.POLY_SIGNATURE,
                L1HeaderBuilder.POLY_TIMESTAMP);
    }
}
