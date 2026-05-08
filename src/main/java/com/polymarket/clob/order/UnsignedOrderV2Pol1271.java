package com.polymarket.clob.order;

import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.exception.ClobSignatureException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;

public record UnsignedOrderV2Pol1271(
        OrderV2 order,
        long    chainId,
        boolean negRisk,
        byte[]  signingDigest32,
        String  typedDataJson,
        byte[]  contentsHash,
        byte[]  appDomainSep,
        String  orderTypeString) {

    public static UnsignedOrderV2Pol1271 buildUnsigned(OrderV2 order, long chainId, boolean negRisk) {
        Objects.requireNonNull(order, "order");
        byte[] contents = Pol1271OrderSigner.contentsHash(order);
        byte[] appSep   = Pol1271OrderSigner.appDomainSeparator(chainId, negRisk);
        byte[] digest   = Pol1271OrderSigner.innerDigest(order, chainId, contents, appSep);
        String json     = Pol1271OrderSigner.typedDataJsonOrderV2Pol1271(order, chainId, negRisk);
        return new UnsignedOrderV2Pol1271(order, chainId, negRisk, digest, json,
                contents, appSep, PolymarketContracts.ORDER_TYPE_STRING);
    }

    public static SignedOrderV2 attachSignature(UnsignedOrderV2Pol1271 unsigned, byte[] innerSig65) {
        Objects.requireNonNull(unsigned, "unsigned");
        if (innerSig65 == null || innerSig65.length != 65) {
            throw new ClobSignatureException(
                    "Pol1271 inner signature must be 65 bytes, got "
                            + (innerSig65 == null ? -1 : innerSig65.length));
        }
        if (unsigned.contentsHash.length != 32 || unsigned.appDomainSep.length != 32) {
            throw new ClobSignatureException("Unsigned record corrupted: helper bytes wrong length");
        }
        byte[] orderTypeAscii = unsigned.orderTypeString.getBytes(StandardCharsets.US_ASCII);
        int len = orderTypeAscii.length;

        ByteBuffer buf = ByteBuffer.allocate(65 + 32 + 32 + len + 2);
        buf.put(innerSig65);
        buf.put(unsigned.appDomainSep);
        buf.put(unsigned.contentsHash);
        buf.put(orderTypeAscii);
        buf.put((byte) ((len >> 8) & 0xff));
        buf.put((byte) (len & 0xff));
        return SignedOrderV2.of(unsigned.order, "0x" + HexFormat.of().formatHex(buf.array()));
    }
}
