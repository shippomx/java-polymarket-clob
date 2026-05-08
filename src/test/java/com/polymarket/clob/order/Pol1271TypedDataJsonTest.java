package com.polymarket.clob.order;

import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

class Pol1271TypedDataJsonTest {

    private static final Address WALLET = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");
    private static final String ZERO32 = "0x" + "00".repeat(32);

    private OrderV2 makeOrder() {
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
    void typedDataJsonRoundTripsToInnerDigest() throws Exception {
        OrderV2 order = makeOrder();
        long chainId = 137L;
        boolean negRisk = false;

        byte[] contentsHash = Pol1271OrderSigner.contentsHash(order);
        byte[] appSep = Pol1271OrderSigner.appDomainSeparator(chainId, negRisk);
        byte[] expected = Pol1271OrderSigner.innerDigest(order, chainId, contentsHash, appSep);

        String json = Pol1271OrderSigner.typedDataJsonOrderV2Pol1271(order, chainId, negRisk);
        byte[] actual = new org.web3j.crypto.StructuredDataEncoder(json).hashStructuredData();
        assertThat(actual).containsExactly(expected);
    }

    @Test
    void typedDataJsonNegRiskUsesDifferentVerifyingContract() {
        OrderV2 order = makeOrder();
        String plain   = Pol1271OrderSigner.typedDataJsonOrderV2Pol1271(order, 137L, false);
        String negRisk = Pol1271OrderSigner.typedDataJsonOrderV2Pol1271(order, 137L, true);
        assertThat(plain).isNotEqualTo(negRisk);
    }
}
