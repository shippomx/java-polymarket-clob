package com.polymarket.clob.order;

import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.exception.ClobSignatureException;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnsignedOrderV2Pol1271Test {

    private static final String PK = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    private static final Address WALLET = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");
    private static final String ZERO32 = "0x" + "00".repeat(32);

    private OrderV2 order() {
        return OrderV2.builder()
                .salt(BigInteger.valueOf(761396384028L))
                .maker(WALLET)
                .signer(WALLET)
                .tokenId(new BigInteger("57597306756265660"))
                .makerAmount(BigInteger.valueOf(1_000_000))
                .takerAmount(BigInteger.valueOf(1_886_700))
                .side(Side.BUY)
                .signatureType(SignatureType.POLY_1271)
                .timestamp(BigInteger.valueOf(1778079826561L))
                .expiration(BigInteger.ZERO)
                .metadata(ZERO32)
                .builder(ZERO32)
                .build();
    }

    @Test
    void buildUnsignedDigestEqualsInnerDigest() {
        OrderV2 o = order();
        UnsignedOrderV2Pol1271 u = UnsignedOrderV2Pol1271.buildUnsigned(o, 137L, false);

        byte[] expectedContents = Pol1271OrderSigner.contentsHash(o);
        byte[] expectedAppSep   = Pol1271OrderSigner.appDomainSeparator(137L, false);
        byte[] expectedDigest   = Pol1271OrderSigner.innerDigest(o, 137L, expectedContents, expectedAppSep);

        assertThat(u.signingDigest32()).hasSize(32).containsExactly(expectedDigest);
        assertThat(u.contentsHash()).hasSize(32).containsExactly(expectedContents);
        assertThat(u.appDomainSep()).hasSize(32).containsExactly(expectedAppSep);
        assertThat(u.orderTypeString())
                .isEqualTo(PolymarketContracts.ORDER_TYPE_STRING);
    }

    @Test
    void attachProducesSameWireAsLocalPol1271OrderSignerSign() {
        LocalSigner s = LocalSigner.fromPrivateKey(PK);
        OrderV2 o = order().toBuilder().maker(s.address()).signer(s.address()).build();

        SignedOrderV2 viaLocal = Pol1271OrderSigner.sign(s, o, 137L, false).join();

        UnsignedOrderV2Pol1271 u = UnsignedOrderV2Pol1271.buildUnsigned(o, 137L, false);
        byte[] sig = s.signHash(u.signingDigest32()).join();
        SignedOrderV2 viaExternal = UnsignedOrderV2Pol1271.attachSignature(u, sig);

        assertThat(viaExternal.getSignature()).isEqualTo(viaLocal.getSignature());
        assertThat(viaExternal.getOrder()).usingRecursiveComparison().isEqualTo(viaLocal.getOrder());
    }

    @Test
    void attachRejectsWrongLengthSig() {
        UnsignedOrderV2Pol1271 u = UnsignedOrderV2Pol1271.buildUnsigned(order(), 137L, false);
        assertThatThrownBy(() -> UnsignedOrderV2Pol1271.attachSignature(u, new byte[64]))
                .isInstanceOf(ClobSignatureException.class);
    }
}
