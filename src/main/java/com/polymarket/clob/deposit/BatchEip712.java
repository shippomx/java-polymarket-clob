package com.polymarket.clob.deposit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.http.JsonCodec;
import com.polymarket.clob.model.Address;
import org.web3j.crypto.Hash;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

/**
 * EIP-712 摘要计算（Deposit Wallet 域 Batch 类型）。
 *
 * <p>类型字符串：
 * <pre>
 * Batch(address wallet,uint256 nonce,uint256 deadline,Call[] calls)Call(address target,uint256 value,bytes data)
 * </pre>
 *
 * <p>注意 {@code Call.data} 是 {@code bytes} 类型 → 712 编码为 {@code keccak256(data)}。
 * {@code Call[]} 数组哈希 = {@code keccak256(concat(callHash_i))}。
 */
public final class BatchEip712 {

    private static final String CALL_TYPE_STRING  = "Call(address target,uint256 value,bytes data)";
    private static final String BATCH_TYPE_STRING =
            "Batch(address wallet,uint256 nonce,uint256 deadline,Call[] calls)" + CALL_TYPE_STRING;

    private static final byte[] CALL_TYPE_HASH  =
            Hash.sha3(CALL_TYPE_STRING.getBytes(StandardCharsets.US_ASCII));
    private static final byte[] BATCH_TYPE_HASH =
            Hash.sha3(BATCH_TYPE_STRING.getBytes(StandardCharsets.US_ASCII));

    private static final String DOMAIN_TYPE_STRING =
            "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)";
    private static final byte[] DOMAIN_TYPE_HASH =
            Hash.sha3(DOMAIN_TYPE_STRING.getBytes(StandardCharsets.US_ASCII));

    private BatchEip712() {}

    public static byte[] hashBatch(long chainId, Address wallet, BigInteger nonce,
                                    BigInteger deadline, List<Call> calls) {
        if (calls == null || calls.isEmpty()) {
            throw new IllegalArgumentException("calls 不能为空");
        }

        // 1. 计算 calls 数组哈希
        ByteBuffer callsBuf = ByteBuffer.allocate(32 * calls.size());
        for (Call c : calls) {
            callsBuf.put(callHash(c));
        }
        byte[] callsHash = Hash.sha3(callsBuf.array());

        // 2. struct hash for Batch
        ByteBuffer batchBuf = ByteBuffer.allocate(32 * 5);
        batchBuf.put(BATCH_TYPE_HASH);
        batchBuf.put(padAddress(wallet));
        batchBuf.put(padUint256(nonce));
        batchBuf.put(padUint256(deadline));
        batchBuf.put(callsHash);
        byte[] structHash = Hash.sha3(batchBuf.array());

        // 3. domain separator
        byte[] domainSeparator = domainSeparator(chainId, wallet);

        // 4. final digest = keccak256(0x1901 || domainSep || structHash)
        ByteBuffer digestBuf = ByteBuffer.allocate(2 + 32 + 32);
        digestBuf.put((byte) 0x19);
        digestBuf.put((byte) 0x01);
        digestBuf.put(domainSeparator);
        digestBuf.put(structHash);
        return Hash.sha3(digestBuf.array());
    }

    static byte[] callHash(Call c) {
        ByteBuffer buf = ByteBuffer.allocate(32 * 4);
        buf.put(CALL_TYPE_HASH);
        buf.put(padAddress(c.target()));
        buf.put(padUint256(c.value()));
        buf.put(Hash.sha3(c.data()));
        return Hash.sha3(buf.array());
    }

    static byte[] domainSeparator(long chainId, Address verifyingContract) {
        ByteBuffer buf = ByteBuffer.allocate(32 * 5);
        buf.put(DOMAIN_TYPE_HASH);
        buf.put(Hash.sha3(PolymarketContracts.DEPOSIT_WALLET_DOMAIN_NAME
                .getBytes(StandardCharsets.UTF_8)));
        buf.put(Hash.sha3(PolymarketContracts.DEPOSIT_WALLET_DOMAIN_VERSION
                .getBytes(StandardCharsets.UTF_8)));
        buf.put(padUint256(BigInteger.valueOf(chainId)));
        buf.put(padAddress(verifyingContract));
        return Hash.sha3(buf.array());
    }

    private static byte[] padAddress(Address a) {
        byte[] out = new byte[32];
        System.arraycopy(a.toBytes(), 0, out, 12, 20);
        return out;
    }

    private static byte[] padUint256(BigInteger v) {
        if (v == null) {
            throw new IllegalArgumentException("uint256 value cannot be null");
        }
        if (v.signum() < 0) {
            throw new IllegalArgumentException("uint256 value cannot be negative: " + v);
        }
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        if (raw.length == 33 && raw[0] == 0) {
            // BigInteger sign byte for values with high bit set; strip it
            System.arraycopy(raw, 1, out, 0, 32);
        } else if (raw.length > 32) {
            throw new IllegalArgumentException(
                    "value exceeds uint256 range (" + raw.length + " bytes): " + v);
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        }
        return out;
    }

    /**
     * 生成符合 EIP-712 规范的 JSON，可直接传入 {@code StructuredDataEncoder} 进行签名。
     *
     * <p>生成的 JSON 与 {@link #hashBatch} 计算的摘要必须完全一致（round-trip invariant）。</p>
     */
    public static String typedDataJsonBatch(long chainId, Address wallet,
                                             BigInteger nonce, BigInteger deadline,
                                             List<Call> calls) {
        if (calls == null || calls.isEmpty()) {
            throw new IllegalArgumentException("calls 不能为空");
        }
        ObjectMapper m = JsonCodec.objectMapper();
        ObjectNode root = m.createObjectNode();

        // types
        ObjectNode types = root.putObject("types");
        ArrayNode domainType = types.putArray("EIP712Domain");
        addType(m, domainType, "name", "string");
        addType(m, domainType, "version", "string");
        addType(m, domainType, "chainId", "uint256");
        addType(m, domainType, "verifyingContract", "address");

        ArrayNode batchType = types.putArray("Batch");
        addType(m, batchType, "wallet", "address");
        addType(m, batchType, "nonce", "uint256");
        addType(m, batchType, "deadline", "uint256");
        addType(m, batchType, "calls", "Call[]");

        ArrayNode callType = types.putArray("Call");
        addType(m, callType, "target", "address");
        addType(m, callType, "value", "uint256");
        addType(m, callType, "data", "bytes");

        root.put("primaryType", "Batch");

        // domain
        ObjectNode domain = root.putObject("domain");
        domain.put("name", PolymarketContracts.DEPOSIT_WALLET_DOMAIN_NAME);
        domain.put("version", PolymarketContracts.DEPOSIT_WALLET_DOMAIN_VERSION);
        domain.put("chainId", chainId);
        domain.put("verifyingContract", wallet.toLowerHex());

        // message
        ObjectNode message = root.putObject("message");
        message.put("wallet", wallet.toLowerHex());
        message.put("nonce", nonce.toString());
        message.put("deadline", deadline.toString());
        ArrayNode callsArr = message.putArray("calls");
        HexFormat hf = HexFormat.of();
        for (Call c : calls) {
            ObjectNode co = m.createObjectNode();
            co.put("target", c.target().toLowerHex());
            co.put("value", c.value().toString());
            co.put("data", "0x" + hf.formatHex(c.data()));
            callsArr.add(co);
        }
        return JsonCodec.writeValue(m, root);
    }

    private static void addType(ObjectMapper m, ArrayNode arr, String name, String type) {
        ObjectNode o = m.createObjectNode();
        o.put("name", name);
        o.put("type", type);
        arr.add(o);
    }
}
