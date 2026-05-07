package com.polymarket.clob.order;

import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class Pol1271SignTest {

    /** 固定测试私钥；EOA 不重要（POLY_1271 maker = wallet）。 */
    private static final String PK_HEX = "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
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
    void signProducesExpectedByteLayout() throws Exception {
        Signer eoa = LocalSigner.fromPrivateKeyHex(PK_HEX);
        SignedOrderV2 signed = Pol1271OrderSigner.sign(eoa, makeOrder(), 137, false).get();

        String hex = signed.getSignature();
        assertThat(hex).startsWith("0x");
        byte[] sig = HexFormat.of().parseHex(hex.substring(2));

        int orderTypeLen = PolymarketContracts.ORDER_TYPE_STRING
                .getBytes(StandardCharsets.US_ASCII).length;
        // 65 + 32 + 32 + N + 2
        assertThat(sig).hasSize(65 + 32 + 32 + orderTypeLen + 2);

        // bytes [129..129+N) 应该 = ORDER_TYPE_STRING ASCII
        byte[] embeddedType = Arrays.copyOfRange(sig, 129, 129 + orderTypeLen);
        assertThat(new String(embeddedType, StandardCharsets.US_ASCII))
                .isEqualTo(PolymarketContracts.ORDER_TYPE_STRING);

        // 末尾 2B = uint16 BE = orderTypeLen
        int hi = sig[sig.length - 2] & 0xff;
        int lo = sig[sig.length - 1] & 0xff;
        assertThat((hi << 8) | lo).isEqualTo(orderTypeLen);

        // 第 65 ~ 97 = appDomainSep (chainId=137, negRisk=false)
        byte[] appSep = Arrays.copyOfRange(sig, 65, 97);
        assertThat(appSep).containsExactly(Pol1271OrderSigner.appDomainSeparator(137, false));

        // 第 97 ~ 129 = contentsHash
        byte[] ch = Arrays.copyOfRange(sig, 97, 129);
        assertThat(ch).containsExactly(Pol1271OrderSigner.contentsHash(makeOrder()));
    }

    @Test
    void negRiskUsesNegRiskAppDomainSep() throws Exception {
        Signer eoa = LocalSigner.fromPrivateKeyHex(PK_HEX);
        SignedOrderV2 signed = Pol1271OrderSigner.sign(eoa, makeOrder(), 137, true).get();
        byte[] sig = HexFormat.of().parseHex(signed.getSignature().substring(2));

        byte[] appSep = Arrays.copyOfRange(sig, 65, 97);
        assertThat(appSep).containsExactly(Pol1271OrderSigner.appDomainSeparator(137, true));
    }

    @Test
    void signedOrderEchoesOriginalOrder() throws Exception {
        Signer eoa = LocalSigner.fromPrivateKeyHex(PK_HEX);
        OrderV2 order = makeOrder();
        SignedOrderV2 signed = Pol1271OrderSigner.sign(eoa, order, 137, false).get();
        assertThat(signed.getOrder()).isEqualTo(order);
    }
}
