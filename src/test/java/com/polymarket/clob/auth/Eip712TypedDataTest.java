package com.polymarket.clob.auth;

import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.ChainId;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class Eip712TypedDataTest {

    private static final String PRIVATE_KEY =
            "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    private static final Address EOA =
            Address.fromHex("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266");

    /**
     * Rust fixture (rs-clob-client/src/auth.rs l1_headers_should_succeed):
     *   chain=AMOY, timestamp=10_000_000, nonce=23
     *   signature = 0xf62319a9...1b  (65 bytes)
     */
    @Test
    void clobAuthSignatureMatchesRustFixture() {
        ClobAuth auth = ClobAuth.of(EOA, 10_000_000L, BigInteger.valueOf(23));
        byte[] digest = Eip712TypedData.hashClobAuth(auth, ChainId.AMOY);
        assertThat(digest).hasSize(32);

        LocalSigner signer = LocalSigner.fromPrivateKey(PRIVATE_KEY);
        byte[] sig = signer.signHash(digest).join();
        assertThat("0x" + HexFormat.of().formatHex(sig)).isEqualTo(
                "0xf62319a987514da40e57e2f4d7529f7bac38f0355bd88bb5adbb3768d80de6c1"
                        + "682518e0af677d5260366425f4361e7b70c25ae232aff0ab2331e2b164a1aedc"
                        + "1b");
    }

    @Test
    void clobAuthDigestIsDeterministic() {
        ClobAuth auth = ClobAuth.of(EOA, 1L, BigInteger.ZERO);
        byte[] a = Eip712TypedData.hashClobAuth(auth, ChainId.POLYGON);
        byte[] b = Eip712TypedData.hashClobAuth(auth, ChainId.POLYGON);
        assertThat(a).containsExactly(b);
    }

    @Test
    void chainIdAffectsDigest() {
        ClobAuth auth = ClobAuth.of(EOA, 1L, BigInteger.ZERO);
        byte[] polygon = Eip712TypedData.hashClobAuth(auth, ChainId.POLYGON);
        byte[] amoy = Eip712TypedData.hashClobAuth(auth, ChainId.AMOY);
        assertThat(polygon).isNotEqualTo(amoy);
    }

    @Test
    void canonicalMessageConstantMatchesProtocol() {
        assertThat(ClobAuth.CANONICAL_MESSAGE)
                .isEqualTo("This message attests that I control the given wallet");
    }

    @Test
    void typedDataJsonClobAuthRoundTripsToHashClobAuth() throws Exception {
        ClobAuth a = ClobAuth.of(EOA, 10_000_000L, BigInteger.valueOf(23));
        long chainId = ChainId.AMOY;
        byte[] expected = Eip712TypedData.hashClobAuth(a, chainId);

        String json = Eip712TypedData.typedDataJsonClobAuth(a, chainId);
        byte[] actual = new org.web3j.crypto.StructuredDataEncoder(json).hashStructuredData();

        assertThat(actual).containsExactly(expected);
    }
}
