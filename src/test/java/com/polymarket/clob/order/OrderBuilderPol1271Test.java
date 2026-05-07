package com.polymarket.clob.order;

import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class OrderBuilderPol1271Test {

    private static final String PK_HEX = "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    private static final Address WALLET = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");

    @Test
    void buildAndSignV2WithPol1271ProducesNestedSignature() throws Exception {
        Signer signer = LocalSigner.fromPrivateKeyHex(PK_HEX);
        OrderBuilder builder = new OrderBuilder(
                137L,
                signer,
                WALLET,
                SignatureType.POLY_1271);

        LimitOrderArgsV2 args = LimitOrderArgsV2.builder()
                .tokenId(new BigInteger("57597306756265660"))
                .side(Side.BUY)
                .price(new BigDecimal("0.5300"))
                .size(new BigDecimal("1"))
                .builderCode("0x" + "00".repeat(32))
                .metadata("0x" + "00".repeat(32))
                .build();

        SignedOrderV2 signed = builder.createOrderV2(args,
                CreateOrderOptions.of(TickSize.TS_0_01, false)).get();

        // maker = signer = wallet（POLY_1271 強制）
        assertThat(signed.getOrder().getMaker()).isEqualTo(WALLET);
        assertThat(signed.getOrder().getSigner()).isEqualTo(WALLET);
        assertThat(signed.getOrder().getSignatureType()).isEqualTo(SignatureType.POLY_1271);

        byte[] sig = HexFormat.of().parseHex(signed.getSignature().substring(2));
        int orderTypeLen = PolymarketContracts.ORDER_TYPE_STRING.getBytes(StandardCharsets.US_ASCII).length;
        assertThat(sig).hasSize(65 + 32 + 32 + orderTypeLen + 2);
    }
}
