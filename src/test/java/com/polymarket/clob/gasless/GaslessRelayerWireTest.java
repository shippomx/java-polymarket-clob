package com.polymarket.clob.gasless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GaslessRelayer#buildSubmitBody} wire JSON 字节级回归。
 *
 * <p>relayer 的 wire 字段顺序与命名是与 npm {@code @polymarket/builder-relayer-client} v0.0.6
 * 严格对齐的；包括 {@code safeTxnGas}（"Txn" 拼写）、SAFE-CREATE 不带 nonce/metadata 等 quirky
 * 约束。任何字段漂移都会让 relayer 服务侧 4xx，因此用 ImmutableObjectMapper 输出做精确字段比对。</p>
 */
class GaslessRelayerWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final long CHAIN_ID = 137;
    private static final Address EOA =
            Address.fromHex("0x0Ba73Fe06B2c537eEEe21690362cF5222d9E5D22");
    private static final Address SAFE =
            Address.fromHex("0x82f55b4bD815FeAEc6E92469c7788Da4E9685D0A");
    private static final Address USDC =
            Address.fromHex("0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174");

    private static byte[] dummySig(int v) {
        byte[] sig = new byte[65];
        for (int i = 0; i < 64; i++) {
            sig[i] = (byte) (i + 1);
        }
        sig[64] = (byte) v;
        return sig;
    }

    // =========================================================
    //  SAFE-CREATE wire 字段顺序
    // =========================================================

    @Test
    void safeCreate_wire_field_order_and_payload() {
        byte[] hash = SafeEip712.createProxyHash(CHAIN_ID,
                Address.fromHex("0xaacFeEa03eb1561C4e67d661e40682Bd20E3541b"));
        SafeTxPayload payload = new SafeTxPayload(
                "SAFE-CREATE",
                EOA,
                SAFE,
                Address.fromHex("0xaacFeEa03eb1561C4e67d661e40682Bd20E3541b"),
                new byte[0],
                0,
                BigInteger.ZERO,
                hash,
                SafeTxStyle.SAFE_CREATE_DIRECT,
                "");

        ObjectNode body = GaslessRelayer.buildSubmitBody(payload, dummySig(28), MAPPER);

        // 字段必须按以下顺序出现（npm builder-relayer-client v0.0.6 buildSafeCreateTransactionRequest）
        Iterator<String> fields = body.fieldNames();
        assertThat(fields.next()).isEqualTo("type");
        assertThat(fields.next()).isEqualTo("from");
        assertThat(fields.next()).isEqualTo("to");
        assertThat(fields.next()).isEqualTo("proxyWallet");
        assertThat(fields.next()).isEqualTo("data");
        assertThat(fields.next()).isEqualTo("signature");
        assertThat(fields.next()).isEqualTo("signatureParams");
        assertThat(fields.hasNext()).as("SAFE-CREATE 不应有 nonce/metadata/value 字段").isFalse();

        // 字段值
        assertThat(body.get("type").asText()).isEqualTo("SAFE-CREATE");
        assertThat(body.get("from").asText()).isEqualTo(EOA.toLowerHex());
        assertThat(body.get("data").asText()).isEqualTo("0x");
        assertThat(body.get("signature").asText()).hasSize(132); // 0x + 130 hex
        assertThat(body.get("signature").asText()).endsWith("1c"); // v=28

        // signatureParams 三字段全 0
        ObjectNode params = (ObjectNode) body.get("signatureParams");
        assertThat(params.get("paymentToken").asText()).isEqualTo(Address.ZERO.toLowerHex());
        assertThat(params.get("payment").asText()).isEqualTo("0");
        assertThat(params.get("paymentReceiver").asText()).isEqualTo(Address.ZERO.toLowerHex());
    }

    // =========================================================
    //  SAFE wire 字段顺序
    // =========================================================

    @Test
    void safe_wire_field_order_and_payload() {
        byte[] data = Calldata.approveErc20(USDC);
        byte[] hash = SafeEip712.safeTxHash(CHAIN_ID, SAFE, USDC, data, 0, BigInteger.valueOf(7));

        SafeTxPayload payload = new SafeTxPayload(
                "SAFE",
                EOA,
                SAFE,
                USDC,
                data,
                0,
                BigInteger.valueOf(7),
                hash,
                SafeTxStyle.SAFE_TX_PERSONAL_SIGN,
                "Setup all approvals");

        ObjectNode body = GaslessRelayer.buildSubmitBody(payload, dummySig(31), MAPPER);

        Iterator<String> fields = body.fieldNames();
        assertThat(fields.next()).isEqualTo("type");
        assertThat(fields.next()).isEqualTo("from");
        assertThat(fields.next()).isEqualTo("to");
        assertThat(fields.next()).isEqualTo("proxyWallet");
        assertThat(fields.next()).isEqualTo("data");
        assertThat(fields.next()).isEqualTo("signature");
        assertThat(fields.next()).isEqualTo("nonce");
        assertThat(fields.next()).isEqualTo("signatureParams");
        assertThat(fields.next()).isEqualTo("metadata");
        assertThat(fields.hasNext()).isFalse();

        assertThat(body.get("type").asText()).isEqualTo("SAFE");
        assertThat(body.get("nonce").asText()).isEqualTo("7");
        assertThat(body.get("metadata").asText()).isEqualTo("Setup all approvals");
        assertThat(body.get("signature").asText()).endsWith("1f"); // v=31
    }

    @Test
    void safe_wire_signatureParams_field_order_and_quirky_safeTxnGas_spelling() {
        SafeTxPayload payload = anySafePayload();
        ObjectNode body = GaslessRelayer.buildSubmitBody(payload, dummySig(31), MAPPER);
        ObjectNode params = (ObjectNode) body.get("signatureParams");

        // 关键：拼写必须是 "safeTxnGas"（带 "n"），与上游 npm 字段名严格一致
        assertThat(params.has("safeTxnGas")).as("拼写错误会导致 relayer 4xx").isTrue();
        assertThat(params.has("safeTxGas")).isFalse();

        Iterator<String> paramFields = params.fieldNames();
        assertThat(paramFields.next()).isEqualTo("gasPrice");
        assertThat(paramFields.next()).isEqualTo("operation");
        assertThat(paramFields.next()).isEqualTo("safeTxnGas");
        assertThat(paramFields.next()).isEqualTo("baseGas");
        assertThat(paramFields.next()).isEqualTo("gasToken");
        assertThat(paramFields.next()).isEqualTo("refundReceiver");

        // 全为 0 / 零地址
        assertThat(params.get("gasPrice").asText()).isEqualTo("0");
        assertThat(params.get("safeTxnGas").asText()).isEqualTo("0");
        assertThat(params.get("baseGas").asText()).isEqualTo("0");
        assertThat(params.get("gasToken").asText()).isEqualTo(Address.ZERO.toLowerHex());
        assertThat(params.get("refundReceiver").asText()).isEqualTo(Address.ZERO.toLowerHex());
    }

    @Test
    void safe_wire_operation_serialized_as_string() {
        // 单笔 Call → operation=0
        SafeTxPayload single = new SafeTxPayload(
                "SAFE", EOA, SAFE, USDC, new byte[]{0x01}, 0, BigInteger.ZERO,
                new byte[32], SafeTxStyle.SAFE_TX_PERSONAL_SIGN, "single");
        ObjectNode singleBody = GaslessRelayer.buildSubmitBody(single, dummySig(31), MAPPER);
        assertThat(singleBody.get("signatureParams").get("operation").asText()).isEqualTo("0");

        // MultiSend DelegateCall → operation=1
        SafeTxPayload multi = new SafeTxPayload(
                "SAFE", EOA, SAFE, MultiSend.MULTISEND_CALL_ONLY, new byte[]{0x02}, 1,
                BigInteger.ZERO, new byte[32], SafeTxStyle.SAFE_TX_PERSONAL_SIGN, "multi");
        ObjectNode multiBody = GaslessRelayer.buildSubmitBody(multi, dummySig(31), MAPPER);
        assertThat(multiBody.get("signatureParams").get("operation").asText()).isEqualTo("1");
    }

    // =========================================================
    //  SafeTxPayload 校验（小集成 smoke）
    // =========================================================

    @Test
    void safeTxPayload_rejects_wrong_hash_length() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new SafeTxPayload("SAFE", EOA, SAFE, USDC, new byte[0], 0, BigInteger.ZERO,
                        new byte[16], SafeTxStyle.SAFE_TX_PERSONAL_SIGN, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("safeTxHash");
    }

    @Test
    void safeTxPayload_rejects_invalid_operation() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new SafeTxPayload("SAFE", EOA, SAFE, USDC, new byte[0], 2, BigInteger.ZERO,
                        new byte[32], SafeTxStyle.SAFE_TX_PERSONAL_SIGN, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operation");
    }

    @Test
    void safeTxPayload_isSafeCreate_matches_style() {
        SafeTxPayload create = new SafeTxPayload("SAFE-CREATE", EOA, SAFE, USDC, new byte[0],
                0, BigInteger.ZERO, new byte[32], SafeTxStyle.SAFE_CREATE_DIRECT, "");
        assertThat(create.isSafeCreate()).isTrue();

        SafeTxPayload exec = anySafePayload();
        assertThat(exec.isSafeCreate()).isFalse();
    }

    private static SafeTxPayload anySafePayload() {
        byte[] data = Calldata.approveErc20(USDC);
        byte[] hash = SafeEip712.safeTxHash(CHAIN_ID, SAFE, USDC, data, 0, BigInteger.ZERO);
        return new SafeTxPayload("SAFE", EOA, SAFE, USDC, data, 0, BigInteger.ZERO,
                hash, SafeTxStyle.SAFE_TX_PERSONAL_SIGN, "approve USDC");
    }

    // 静态方法表达 List 用法以避免冗余 import
    @SuppressWarnings("unused")
    private static <T> List<T> only(T item) {
        return List.of(item);
    }
}
