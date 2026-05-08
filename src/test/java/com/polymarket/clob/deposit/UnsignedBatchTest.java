package com.polymarket.clob.deposit;

import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.exception.ClobSignatureException;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnsignedBatchTest {

    private static final String PK = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";

    private List<Call> sampleCalls() {
        return List.of(new Call(
                Address.fromHex("0x1111111111111111111111111111111111111111"),
                BigInteger.ZERO,
                new byte[]{0x01, 0x02, 0x03}));
    }

    @Test
    void digestEqualsBatchEip712Hash() {
        Address eoa = Address.fromHex("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266");
        Address wallet = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");
        UnsignedBatch u = UnsignedBatch.buildUnsigned(137L, eoa, wallet, BigInteger.valueOf(7),
                BigInteger.valueOf(2_000_000_000L), sampleCalls());
        byte[] expected = BatchEip712.hashBatch(137L, wallet, BigInteger.valueOf(7),
                BigInteger.valueOf(2_000_000_000L), sampleCalls());
        assertThat(u.signingDigest32()).containsExactly(expected);
    }

    @Test
    void typedDataJsonRoundTripsToDigest() throws Exception {
        Address wallet = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");
        UnsignedBatch u = UnsignedBatch.buildUnsigned(137L,
                Address.fromHex("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266"),
                wallet, BigInteger.valueOf(7), BigInteger.valueOf(2_000_000_000L), sampleCalls());
        byte[] roundTrip = new org.web3j.crypto.StructuredDataEncoder(u.typedDataJson()).hashStructuredData();
        assertThat(roundTrip).containsExactly(u.signingDigest32());
    }

    @Test
    void attachReturnsSignedBatchWithCorrectFields() {
        LocalSigner s = LocalSigner.fromPrivateKey(PK);
        Address wallet = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");
        UnsignedBatch u = UnsignedBatch.buildUnsigned(137L, s.address(), wallet, BigInteger.valueOf(7),
                BigInteger.valueOf(2_000_000_000L), sampleCalls());
        byte[] sig = s.signHash(u.signingDigest32()).join();

        SignedBatch signed = UnsignedBatch.attachSignature(u, sig);
        assertThat(signed.eoa()).isEqualTo(s.address());
        assertThat(signed.factory()).isEqualTo(PolymarketContracts.FACTORY);
        assertThat(signed.wallet()).isEqualTo(wallet);
        assertThat(signed.nonce()).isEqualTo(BigInteger.valueOf(7));
        assertThat(signed.deadline()).isEqualTo(BigInteger.valueOf(2_000_000_000L));
        assertThat(signed.calls()).hasSize(1);
        assertThat(signed.signature65()).hasSize(65).containsExactly(sig);
    }

    @Test
    void attachRejectsWrongLengthSig() {
        Address eoa = Address.fromHex("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266");
        Address wallet = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");
        UnsignedBatch u = UnsignedBatch.buildUnsigned(137L, eoa, wallet, BigInteger.ZERO,
                BigInteger.ZERO, sampleCalls());
        assertThatThrownBy(() -> UnsignedBatch.attachSignature(u, new byte[64]))
                .isInstanceOf(ClobSignatureException.class);
    }

    @Test
    void buildRejectsEmptyCalls() {
        Address eoa = Address.fromHex("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266");
        Address wallet = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");
        assertThatThrownBy(() -> UnsignedBatch.buildUnsigned(137L, eoa, wallet, BigInteger.ZERO,
                BigInteger.ZERO, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
