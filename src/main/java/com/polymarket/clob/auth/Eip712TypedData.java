package com.polymarket.clob.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.polymarket.clob.exception.ClobSignatureException;
import com.polymarket.clob.http.JsonCodec;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.order.Order;
import com.polymarket.clob.order.OrderV2;
import org.web3j.crypto.StructuredDataEncoder;

import java.io.IOException;
import java.util.Objects;

/**
 * EIP-712 typed-data 构造与摘要工具。
 *
 * <p>两个入口：
 * <ul>
 *   <li>{@link #hashClobAuth(ClobAuth, long)} — L1 登录签名（Plan 2）；</li>
 *   <li>{@link #hashOrder(Order, long, Address)} — Order EIP-712 签名（Plan 3），
 *       {@code verifyingContract} 取自 {@code ContractRegistry} 的 CTFExchange 或
 *       NegRiskCTFExchange，由上层按 {@code negRisk} 选择。</li>
 * </ul>
 * 两条路径都返回 32 字节摘要；共享 {@code addField(...)} 小工具避免重复造轮子。</p>
 *
 * <p>底层依赖 Web3j {@link StructuredDataEncoder}，它遵循 EIP-712 规范做
 * {@code domainSeparator} + {@code hashStruct} 的组合并返回 32 字节摘要。</p>
 */
public final class Eip712TypedData {

    private Eip712TypedData() {}

    public static String typedDataJsonClobAuth(ClobAuth auth, long chainId) {
        Objects.requireNonNull(auth, "auth");
        ObjectMapper m = JsonCodec.objectMapper();
        ObjectNode root = m.createObjectNode();

        ObjectNode types = root.putObject("types");
        ArrayNode domainType = types.putArray("EIP712Domain");
        addField(m, domainType, "name", "string");
        addField(m, domainType, "version", "string");
        addField(m, domainType, "chainId", "uint256");
        ArrayNode clobAuthType = types.putArray("ClobAuth");
        addField(m, clobAuthType, "address", "address");
        addField(m, clobAuthType, "timestamp", "string");
        addField(m, clobAuthType, "nonce", "uint256");
        addField(m, clobAuthType, "message", "string");

        root.put("primaryType", "ClobAuth");

        ObjectNode domain = root.putObject("domain");
        domain.put("name", ClobAuth.DOMAIN_NAME);
        domain.put("version", ClobAuth.DOMAIN_VERSION);
        domain.put("chainId", chainId);

        ObjectNode message = root.putObject("message");
        message.put("address", auth.address().toHex());
        message.put("timestamp", auth.timestamp());
        message.put("nonce", auth.nonce());
        message.put("message", auth.message());

        return JsonCodec.writeValue(m, root);
    }

    public static byte[] hashClobAuth(ClobAuth auth, long chainId) {
        String json = typedDataJsonClobAuth(auth, chainId);
        try {
            return new StructuredDataEncoder(json).hashStructuredData();
        } catch (IOException | RuntimeException e) {
            throw new ClobSignatureException("Failed to encode EIP-712 ClobAuth", e);
        }
    }

    /**
     * 计算 Polymarket CTF Exchange 订单的 EIP-712 struct hash。
     *
     * <p>与 python-order-utils 的 {@code OrderBuilder._create_struct_hash} 按位一致：
     * 域使用 {@code name="Polymarket CTF Exchange"}, {@code version="1"}；{@code
     * verifyingContract} 由 {@link com.polymarket.clob.model.ContractRegistry}
     * 根据 {@code negRisk} 提供（CTFExchange vs NegRiskCTFExchange）。</p>
     *
     * <p>类型里 {@code uint8}（{@code side} / {@code signatureType}）与其余 {@code uint256}
     * 的 ABI 编码在 Web3j {@link StructuredDataEncoder} 内部按 EIP-712 规范自动处理；
     * 大整数以十进制字符串传入即可。</p>
     */
    public static byte[] hashOrder(Order order, long chainId, Address verifyingContract) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(verifyingContract, "verifyingContract");

        ObjectMapper m = JsonCodec.objectMapper();
        ObjectNode root = m.createObjectNode();

        ObjectNode types = root.putObject("types");
        ArrayNode domainType = types.putArray("EIP712Domain");
        addField(m, domainType, "name", "string");
        addField(m, domainType, "version", "string");
        addField(m, domainType, "chainId", "uint256");
        addField(m, domainType, "verifyingContract", "address");

        ArrayNode orderType = types.putArray("Order");
        addField(m, orderType, "salt", "uint256");
        addField(m, orderType, "maker", "address");
        addField(m, orderType, "signer", "address");
        addField(m, orderType, "taker", "address");
        addField(m, orderType, "tokenId", "uint256");
        addField(m, orderType, "makerAmount", "uint256");
        addField(m, orderType, "takerAmount", "uint256");
        addField(m, orderType, "expiration", "uint256");
        addField(m, orderType, "nonce", "uint256");
        addField(m, orderType, "feeRateBps", "uint256");
        addField(m, orderType, "side", "uint8");
        addField(m, orderType, "signatureType", "uint8");

        root.put("primaryType", "Order");

        ObjectNode domain = root.putObject("domain");
        domain.put("name", "Polymarket CTF Exchange");
        domain.put("version", "1");
        domain.put("chainId", chainId);
        domain.put("verifyingContract", verifyingContract.toLowerHex());

        ObjectNode message = root.putObject("message");
        message.put("salt", order.getSalt().toString());
        message.put("maker", order.getMaker().toLowerHex());
        message.put("signer", order.getSigner().toLowerHex());
        message.put("taker", order.getTaker().toLowerHex());
        message.put("tokenId", order.getTokenId().toString());
        message.put("makerAmount", order.getMakerAmount().toString());
        message.put("takerAmount", order.getTakerAmount().toString());
        message.put("expiration", order.getExpiration().toString());
        message.put("nonce", order.getNonce().toString());
        message.put("feeRateBps", order.getFeeRateBps().toString());
        message.put("side", order.getSide().exchangeCode());
        message.put("signatureType", order.getSignatureType().code());

        String json = JsonCodec.writeValue(m, root);
        try {
            return new StructuredDataEncoder(json).hashStructuredData();
        } catch (IOException | RuntimeException e) {
            throw new ClobSignatureException("Failed to encode EIP-712 Order", e);
        }
    }

    /**
     * 计算 Polymarket CTF Exchange v2 订单的 EIP-712 struct hash。
     *
     * <p>与 py-clob-client-v2 {@code ctf_exchange_v2_typed_data} / rs-clob-client-v2 v2 路径一致：
     * <ul>
     *   <li>domain {@code name="Polymarket CTF Exchange"}, <b>{@code version="2"}</b>；</li>
     *   <li>{@code verifyingContract} 由 {@link com.polymarket.clob.model.ContractRegistry#exchangeV2(long, boolean)}
     *       根据 {@code negRisk} 提供；</li>
     *   <li>Order struct 11 字段：{@code salt / maker / signer / tokenId / makerAmount / takerAmount /
     *       side(uint8) / signatureType(uint8) / timestamp / metadata(bytes32) / builder(bytes32)}。
     *       <b>不包含</b> {@code taker / expiration / nonce / feeRateBps}（这些在 v2 已被移除/wire-only）。</li>
     * </ul>
     * </p>
     *
     * <p>Web3j {@link StructuredDataEncoder} 不直接支持 {@code bytes32} 输入字符串：上游期待 0x-hex 输入并按 32
     * 字节左零填补。本方法将 {@link OrderV2#getMetadata()} / {@link OrderV2#getBuilder()} 的 hex
     * 字符串原样写入 message，由 Encoder 内部按规范 ABI 编码处理；非 0x66 字符的非法值会
     * 在 hash 阶段抛出，调用方应在构造 OrderV2 时校验。</p>
     */
    public static byte[] hashOrderV2(OrderV2 order, long chainId, Address verifyingContract) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(verifyingContract, "verifyingContract");

        ObjectMapper m = JsonCodec.objectMapper();
        ObjectNode root = m.createObjectNode();

        ObjectNode types = root.putObject("types");
        ArrayNode domainType = types.putArray("EIP712Domain");
        addField(m, domainType, "name", "string");
        addField(m, domainType, "version", "string");
        addField(m, domainType, "chainId", "uint256");
        addField(m, domainType, "verifyingContract", "address");

        ArrayNode orderType = types.putArray("Order");
        addField(m, orderType, "salt", "uint256");
        addField(m, orderType, "maker", "address");
        addField(m, orderType, "signer", "address");
        addField(m, orderType, "tokenId", "uint256");
        addField(m, orderType, "makerAmount", "uint256");
        addField(m, orderType, "takerAmount", "uint256");
        addField(m, orderType, "side", "uint8");
        addField(m, orderType, "signatureType", "uint8");
        addField(m, orderType, "timestamp", "uint256");
        addField(m, orderType, "metadata", "bytes32");
        addField(m, orderType, "builder", "bytes32");

        root.put("primaryType", "Order");

        ObjectNode domain = root.putObject("domain");
        domain.put("name", "Polymarket CTF Exchange");
        domain.put("version", "2");
        domain.put("chainId", chainId);
        domain.put("verifyingContract", verifyingContract.toLowerHex());

        ObjectNode message = root.putObject("message");
        message.put("salt", order.getSalt().toString());
        message.put("maker", order.getMaker().toLowerHex());
        message.put("signer", order.getSigner().toLowerHex());
        message.put("tokenId", order.getTokenId().toString());
        message.put("makerAmount", order.getMakerAmount().toString());
        message.put("takerAmount", order.getTakerAmount().toString());
        message.put("side", order.getSide().exchangeCode());
        message.put("signatureType", order.getSignatureType().code());
        message.put("timestamp", order.getTimestamp().toString());
        message.put("metadata", order.getMetadata());
        message.put("builder", order.getBuilder());

        String json = JsonCodec.writeValue(m, root);
        try {
            return new StructuredDataEncoder(json).hashStructuredData();
        } catch (IOException | RuntimeException e) {
            throw new ClobSignatureException("Failed to encode EIP-712 OrderV2", e);
        }
    }

    /** 追加一个 {@code {"name": n, "type": t}} 到类型数组。 */
    private static void addField(ObjectMapper m, ArrayNode arr, String name, String type) {
        ObjectNode o = m.createObjectNode();
        o.put("name", name);
        o.put("type", type);
        arr.add(o);
    }
}
