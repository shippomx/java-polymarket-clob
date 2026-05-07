package com.polymarket.clob.order;

import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.exception.ClobSignatureException;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.ContractRegistry;
import org.web3j.crypto.Hash;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

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

    private static final String DOMAIN_TYPE_STRING =
            "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)";
    private static final byte[] DOMAIN_TYPE_HASH =
            Hash.sha3(DOMAIN_TYPE_STRING.getBytes(StandardCharsets.US_ASCII));

    private static final byte[] CTF_EXCHANGE_NAME_HASH =
            Hash.sha3(PolymarketContracts.CTF_EXCHANGE_V2_DOMAIN_NAME.getBytes(StandardCharsets.UTF_8));
    private static final byte[] CTF_EXCHANGE_VERSION_HASH =
            Hash.sha3(PolymarketContracts.CTF_EXCHANGE_V2_DOMAIN_VERSION.getBytes(StandardCharsets.UTF_8));

    private static final Map<String, byte[]> APP_DOMAIN_SEP_CACHE = new ConcurrentHashMap<>();

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
        String key = chainId + "|" + negRisk;
        return APP_DOMAIN_SEP_CACHE.computeIfAbsent(key, k -> {
            Address verifyingContract = ContractRegistry.exchangeV2(chainId, negRisk)
                    .orElseThrow(() -> new ClobSignatureException(
                            "exchangeV2 not registered for chainId=" + chainId + " negRisk=" + negRisk));
            ByteBuffer buf = ByteBuffer.allocate(32 * 5);
            buf.put(DOMAIN_TYPE_HASH);
            buf.put(CTF_EXCHANGE_NAME_HASH);
            buf.put(CTF_EXCHANGE_VERSION_HASH);
            buf.put(padUint(BigInteger.valueOf(chainId)));
            buf.put(padAddress(verifyingContract));
            return Hash.sha3(buf.array());
        });
    }

    /** ERC-7739 nested TypedDataSign 全签名。 */
    public static CompletableFuture<SignedOrderV2> sign(Signer eoa, OrderV2 order,
                                                         long chainId, boolean negRisk) {
        try {
            byte[] contents = contentsHash(order);
            byte[] appSep   = appDomainSeparator(chainId, negRisk);
            byte[] digest   = innerDigest(order, chainId, negRisk, contents, appSep);
            return eoa.signHash(digest).thenApply(innerSig -> {
                if (innerSig == null || innerSig.length != 65) {
                    throw new ClobSignatureException("inner signer returned length="
                            + (innerSig == null ? -1 : innerSig.length));
                }
                byte[] orderTypeAscii = PolymarketContracts.ORDER_TYPE_STRING
                        .getBytes(StandardCharsets.US_ASCII);
                int len = orderTypeAscii.length;

                ByteBuffer buf = ByteBuffer.allocate(65 + 32 + 32 + len + 2);
                buf.put(innerSig);
                buf.put(appSep);
                buf.put(contents);
                buf.put(orderTypeAscii);
                buf.put((byte) ((len >> 8) & 0xff));
                buf.put((byte) (len & 0xff));
                String hex = "0x" + HexFormat.of().formatHex(buf.array());
                return SignedOrderV2.of(order, hex);
            });
        } catch (ClobSignatureException e) {
            return CompletableFuture.failedFuture(e);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(
                    new ClobSignatureException("Pol1271OrderSigner.sign failed", e));
        }
    }

    /**
     * 内层 TypedDataSign 摘要：
     *   structHash = keccak256(TYPED_DATA_SIGN_TYPE_HASH || contentsHash
     *                          || keccak256("DepositWallet") || keccak256("1")
     *                          || pad32(chainId) || pad32(wallet) || zero32)
     *   digest     = keccak256(0x1901 || appDomainSep || structHash)
     *
     * 钱包域用作 TypedDataSign value 的内嵌 domain：
     *   name="DepositWallet", version="1", chainId, verifyingContract=order.signer (=wallet),
     *   salt=bytes32(0)。
     */
    static byte[] innerDigest(OrderV2 order, long chainId, boolean negRisk,
                               byte[] contentsHash, byte[] appDomainSep) {
        byte[] depositNameHash = Hash.sha3(
                PolymarketContracts.DEPOSIT_WALLET_DOMAIN_NAME.getBytes(StandardCharsets.UTF_8));
        byte[] depositVersionHash = Hash.sha3(
                PolymarketContracts.DEPOSIT_WALLET_DOMAIN_VERSION.getBytes(StandardCharsets.UTF_8));
        byte[] zero32 = new byte[32];

        ByteBuffer structBuf = ByteBuffer.allocate(32 * 7);
        structBuf.put(PolymarketContracts.TYPED_DATA_SIGN_TYPE_HASH);
        structBuf.put(contentsHash);
        structBuf.put(depositNameHash);
        structBuf.put(depositVersionHash);
        structBuf.put(padUint(BigInteger.valueOf(chainId)));
        structBuf.put(padAddress(order.getSigner()));
        structBuf.put(zero32);
        byte[] structHash = Hash.sha3(structBuf.array());

        ByteBuffer digestBuf = ByteBuffer.allocate(2 + 32 + 32);
        digestBuf.put((byte) 0x19);
        digestBuf.put((byte) 0x01);
        digestBuf.put(appDomainSep);
        digestBuf.put(structHash);
        return Hash.sha3(digestBuf.array());
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
