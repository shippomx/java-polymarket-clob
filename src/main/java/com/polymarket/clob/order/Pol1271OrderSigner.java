package com.polymarket.clob.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.exception.ClobSignatureException;
import com.polymarket.clob.http.JsonCodec;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.ContractRegistry;
import org.web3j.crypto.Hash;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
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
    public static byte[] appDomainSeparator(long chainId, boolean negRisk) {
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
            byte[] digest   = innerDigest(order, chainId, contents, appSep);
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
    public static byte[] innerDigest(OrderV2 order, long chainId,
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

    /**
     * 生成 ERC-7739 TypedDataSign EIP-712 JSON，可通过 web3j {@code StructuredDataEncoder}
     * round-trip 到与 {@link #innerDigest} 相同的 32 字节摘要。
     */
    public static String typedDataJsonOrderV2Pol1271(OrderV2 order, long chainId, boolean negRisk) {
        Objects.requireNonNull(order, "order");
        Address verifyingContract = ContractRegistry.exchangeV2(chainId, negRisk).orElseThrow(
                () -> new ClobSignatureException(
                        "exchangeV2 not registered for chainId=" + chainId + " negRisk=" + negRisk));

        ObjectMapper m = JsonCodec.objectMapper();
        ObjectNode root = m.createObjectNode();

        ObjectNode types = root.putObject("types");
        ArrayNode domainType = types.putArray("EIP712Domain");
        addTypeField(m, domainType, "name", "string");
        addTypeField(m, domainType, "version", "string");
        addTypeField(m, domainType, "chainId", "uint256");
        addTypeField(m, domainType, "verifyingContract", "address");

        ArrayNode tdsType = types.putArray("TypedDataSign");
        addTypeField(m, tdsType, "contents", "Order");
        addTypeField(m, tdsType, "name", "string");
        addTypeField(m, tdsType, "version", "string");
        addTypeField(m, tdsType, "chainId", "uint256");
        addTypeField(m, tdsType, "verifyingContract", "address");
        addTypeField(m, tdsType, "salt", "bytes32");

        ArrayNode orderType = types.putArray("Order");
        addTypeField(m, orderType, "salt", "uint256");
        addTypeField(m, orderType, "maker", "address");
        addTypeField(m, orderType, "signer", "address");
        addTypeField(m, orderType, "tokenId", "uint256");
        addTypeField(m, orderType, "makerAmount", "uint256");
        addTypeField(m, orderType, "takerAmount", "uint256");
        addTypeField(m, orderType, "side", "uint8");
        addTypeField(m, orderType, "signatureType", "uint8");
        addTypeField(m, orderType, "timestamp", "uint256");
        addTypeField(m, orderType, "metadata", "bytes32");
        addTypeField(m, orderType, "builder", "bytes32");

        root.put("primaryType", "TypedDataSign");

        ObjectNode domain = root.putObject("domain");
        domain.put("name", PolymarketContracts.CTF_EXCHANGE_V2_DOMAIN_NAME);
        domain.put("version", PolymarketContracts.CTF_EXCHANGE_V2_DOMAIN_VERSION);
        domain.put("chainId", chainId);
        domain.put("verifyingContract", verifyingContract.toLowerHex());

        ObjectNode message = root.putObject("message");
        ObjectNode contents = message.putObject("contents");
        contents.put("salt", order.getSalt().toString());
        contents.put("maker", order.getMaker().toLowerHex());
        contents.put("signer", order.getSigner().toLowerHex());
        contents.put("tokenId", order.getTokenId().toString());
        contents.put("makerAmount", order.getMakerAmount().toString());
        contents.put("takerAmount", order.getTakerAmount().toString());
        contents.put("side", order.getSide().exchangeCode());
        contents.put("signatureType", order.getSignatureType().code());
        contents.put("timestamp", order.getTimestamp().toString());
        contents.put("metadata", order.getMetadata());
        contents.put("builder", order.getBuilder());

        message.put("name", PolymarketContracts.DEPOSIT_WALLET_DOMAIN_NAME);
        message.put("version", PolymarketContracts.DEPOSIT_WALLET_DOMAIN_VERSION);
        message.put("chainId", chainId);
        message.put("verifyingContract", order.getSigner().toLowerHex());
        message.put("salt", "0x" + "00".repeat(32));

        return JsonCodec.writeValue(m, root);
    }

    private static void addTypeField(ObjectMapper m, ArrayNode arr, String name, String type) {
        ObjectNode o = m.createObjectNode();
        o.put("name", name);
        o.put("type", type);
        arr.add(o);
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
