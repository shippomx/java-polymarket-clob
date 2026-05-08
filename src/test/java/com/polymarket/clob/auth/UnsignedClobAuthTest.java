package com.polymarket.clob.auth;

import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.ChainId;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnsignedClobAuthTest {

    private static final Address EOA  = Address.fromHex("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266");
    private static final String  PK   = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";

    @Test
    void buildUnsignedDigestEqualsHashClobAuth() {
        UnsignedClobAuth u = UnsignedClobAuth.buildUnsigned(EOA, ChainId.AMOY, 10_000_000L, BigInteger.valueOf(23));
        byte[] expected = Eip712TypedData.hashClobAuth(ClobAuth.of(EOA, 10_000_000L, BigInteger.valueOf(23)), ChainId.AMOY);
        assertThat(u.signingDigest32()).containsExactly(expected);
        assertThat(u.signingDigest32()).hasSize(32);
    }

    @Test
    void typedDataJsonRoundTripsToDigest() throws Exception {
        UnsignedClobAuth u = UnsignedClobAuth.buildUnsigned(EOA, ChainId.POLYGON, 1L, BigInteger.ZERO);
        byte[] roundTrip = new org.web3j.crypto.StructuredDataEncoder(u.typedDataJson()).hashStructuredData();
        assertThat(roundTrip).containsExactly(u.signingDigest32());
    }

    @Test
    void attachReturnsSamePolyHeadersAsLocalSignerPath() {
        LocalSigner s = LocalSigner.fromPrivateKey(PK);

        Map<String, String> localPath = L1HeaderBuilder.build(s, ChainId.AMOY, 10_000_000L, BigInteger.valueOf(23)).join();

        UnsignedClobAuth u = UnsignedClobAuth.buildUnsigned(s.address(), ChainId.AMOY, 10_000_000L, BigInteger.valueOf(23));
        byte[] sig = s.signHash(u.signingDigest32()).join();
        Map<String, String> external = UnsignedClobAuth.attachSignature(u, sig);

        assertThat(external).containsExactlyEntriesOf(localPath);
    }

    @Test
    void attachRejectsWrongLengthSig() {
        UnsignedClobAuth u = UnsignedClobAuth.buildUnsigned(EOA, ChainId.AMOY, 10_000_000L, BigInteger.valueOf(23));
        assertThatThrownBy(() -> UnsignedClobAuth.attachSignature(u, new byte[64]))
                .isInstanceOf(com.polymarket.clob.exception.ClobSignatureException.class);
    }

    @Test
    void nullNonceDefaultsToZero() {
        UnsignedClobAuth a = UnsignedClobAuth.buildUnsigned(EOA, ChainId.POLYGON, 1L, null);
        UnsignedClobAuth b = UnsignedClobAuth.buildUnsigned(EOA, ChainId.POLYGON, 1L, BigInteger.ZERO);
        assertThat(a.signingDigest32()).containsExactly(b.signingDigest32());
        assertThat(a.nonce()).isEqualTo(BigInteger.ZERO);
    }
}
