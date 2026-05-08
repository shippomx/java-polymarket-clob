package com.polymarket.clob.gamma;

import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.exception.ClobSignatureException;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.ChainId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnsignedSiweTest {

    private static final String PK = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";

    private static final Address EOA = Address.fromHex("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266");
    private static final Instant ISSUED  = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-01-08T00:00:00Z");

    @Test
    void canonicalMessageMatchesSiweMessageBuild() {
        UnsignedSiwe u = UnsignedSiwe.buildUnsigned(EOA, ChainId.POLYGON, "abc123", ISSUED, EXPIRES);
        String expected = SiweMessage.build(EOA, ChainId.POLYGON, "abc123", ISSUED, EXPIRES);
        assertThat(u.canonicalMessage()).isEqualTo(expected);
    }

    @Test
    void digestMatchesPersonalSignOfCanonicalMessage() {
        UnsignedSiwe u = UnsignedSiwe.buildUnsigned(EOA, ChainId.POLYGON, "abc123", ISSUED, EXPIRES);
        byte[] expected = SiweMessage.personalSignDigest(u.canonicalMessage());
        assertThat(u.signingDigest32()).hasSize(32).containsExactly(expected);
    }

    @Test
    void attachReturnsSignedSiweWithSameSig() {
        LocalSigner s = LocalSigner.fromPrivateKey(PK);
        UnsignedSiwe u = UnsignedSiwe.buildUnsigned(s.address(), ChainId.POLYGON, "abc123", ISSUED, EXPIRES);
        byte[] sig = s.signHash(u.signingDigest32()).join();

        SignedSiwe signed = UnsignedSiwe.attachSignature(u, sig);

        assertThat(signed.canonicalMessage()).isEqualTo(u.canonicalMessage());
        assertThat(signed.signatureHex())
                .startsWith("0x")
                .hasSize(2 + 130);
        assertThat(HexFormat.of().parseHex(signed.signatureHex().substring(2)))
                .containsExactly(sig);
    }

    @Test
    void attachRejectsWrongLengthSig() {
        UnsignedSiwe u = UnsignedSiwe.buildUnsigned(EOA, ChainId.POLYGON, "n", ISSUED, EXPIRES);
        assertThatThrownBy(() -> UnsignedSiwe.attachSignature(u, new byte[64]))
                .isInstanceOf(ClobSignatureException.class);
    }
}
