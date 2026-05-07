package com.polymarket.clob.order;

import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Hash;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class Pol1271ContentsHashTest {

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
    void contentsHashIs32Bytes() {
        byte[] h = Pol1271OrderSigner.contentsHash(makeOrder());
        assertThat(h).hasSize(32);
    }

    @Test
    void contentsHashEqualsManualEncoding() {
        OrderV2 o = makeOrder();
        // 手算：keccak256(abi.encode(ORDER_TYPE_HASH, salt, maker, signer, tokenId,
        //                            makerAmount, takerAmount, side(uint8), sigType(uint8),
        //                            timestamp, metadata, builder))
        ByteBuffer buf = ByteBuffer.allocate(32 * 12);
        buf.put(PolymarketContracts.ORDER_TYPE_HASH);
        buf.put(padUint(o.getSalt()));
        buf.put(padAddress(o.getMaker()));
        buf.put(padAddress(o.getSigner()));
        buf.put(padUint(o.getTokenId()));
        buf.put(padUint(o.getMakerAmount()));
        buf.put(padUint(o.getTakerAmount()));
        buf.put(padUint(BigInteger.valueOf(o.getSide() == Side.BUY ? 0 : 1)));
        buf.put(padUint(BigInteger.valueOf(o.getSignatureType().code())));
        buf.put(padUint(o.getTimestamp()));
        buf.put(parseBytes32(o.getMetadata()));
        buf.put(parseBytes32(o.getBuilder()));
        byte[] expected = Hash.sha3(buf.array());

        assertThat(Pol1271OrderSigner.contentsHash(o)).containsExactly(expected);
    }

    @Test
    void contentsHashChangesWithSide() {
        OrderV2 buy = makeOrder();
        OrderV2 sell = buy.toBuilder().side(Side.SELL).build();
        assertThat(Pol1271OrderSigner.contentsHash(buy))
                .isNotEqualTo(Pol1271OrderSigner.contentsHash(sell));
    }

    private static byte[] padUint(BigInteger v) {
        byte[] raw = v.toByteArray(); byte[] out = new byte[32];
        if (raw.length > 32) System.arraycopy(raw, raw.length - 32, out, 0, 32);
        else System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }

    private static byte[] padAddress(Address a) {
        byte[] out = new byte[32];
        System.arraycopy(a.toBytes(), 0, out, 12, 20);
        return out;
    }

    private static byte[] parseBytes32(String hex) {
        byte[] out = java.util.HexFormat.of().parseHex(hex.substring(2));
        if (out.length != 32) throw new IllegalArgumentException();
        return out;
    }
}
