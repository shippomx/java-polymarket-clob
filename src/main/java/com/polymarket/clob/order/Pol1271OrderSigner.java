package com.polymarket.clob.order;

import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.exception.ClobSignatureException;
import com.polymarket.clob.model.Address;
import org.web3j.crypto.Hash;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;

/**
 * POLY_1271 嵌套签名（ERC-7739 TypedDataSign）。
 *
 * <p>最终 signature 字节序列：
 * {@code innerSig(65) || appDomainSep(32) || contentsHash(32) || ORDER_TYPE_STRING || lenBE(2)}
 *
 * <p>实现严格复刻 clob-client-v2/src/order-utils/exchangeOrderBuilderV2.ts:147-221。
 */
public final class Pol1271OrderSigner {

    private Pol1271OrderSigner() {}

    /**
     * 计算 contents hash —— Order struct 的 EIP-712 struct hash。
     * keccak256(abi.encode(ORDER_TYPE_HASH, salt, maker, signer, tokenId,
     *                       makerAmount, takerAmount, side, sigType, timestamp,
     *                       metadata, builder))
     */
    public static byte[] contentsHash(OrderV2 order) {
        ByteBuffer buf = ByteBuffer.allocate(32 * 12);
        buf.put(PolymarketContracts.ORDER_TYPE_HASH);
        buf.put(padUint(order.getSalt()));
        buf.put(padAddress(order.getMaker()));
        buf.put(padAddress(order.getSigner()));
        buf.put(padUint(order.getTokenId()));
        buf.put(padUint(order.getMakerAmount()));
        buf.put(padUint(order.getTakerAmount()));
        buf.put(padUint(BigInteger.valueOf(sideCode(order.getSide()))));
        buf.put(padUint(BigInteger.valueOf(order.getSignatureType().code())));
        buf.put(padUint(order.getTimestamp()));
        buf.put(parseBytes32(order.getMetadata(), "metadata"));
        buf.put(parseBytes32(order.getBuilder(), "builder"));
        return Hash.sha3(buf.array());
    }

    /** Task 16 实现：appDomainSep 缓存。 */
    static byte[] appDomainSeparator(long chainId, boolean negRisk) {
        throw new UnsupportedOperationException("Implemented in Task 16");
    }

    /** Task 17 实现：完整 sign 流程。 */
    public static CompletableFuture<SignedOrderV2> sign(Signer eoa, OrderV2 order,
                                                         long chainId, boolean negRisk) {
        throw new UnsupportedOperationException("Implemented in Task 17");
    }

    // ---- helpers ----

    static int sideCode(Side s) { return s == Side.BUY ? 0 : 1; }

    static byte[] padUint(BigInteger v) {
        byte[] raw = v.toByteArray(); byte[] out = new byte[32];
        if (raw.length > 32) System.arraycopy(raw, raw.length - 32, out, 0, 32);
        else System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }

    static byte[] padAddress(Address a) {
        byte[] out = new byte[32];
        System.arraycopy(a.toBytes(), 0, out, 12, 20);
        return out;
    }

    static byte[] parseBytes32(String hex, String fieldName) {
        if (hex == null || !hex.startsWith("0x") || hex.length() != 66) {
            throw new ClobSignatureException(fieldName + " must be 0x-prefixed 32B hex (66 chars)");
        }
        return HexFormat.of().parseHex(hex.substring(2));
    }
}
