package com.polymarket.clob.gasless;

import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MultiSend} 编码回归。Polymarket relayer 严格按 EIP-2020 内部布局 + 外层
 * {@code multiSend(bytes)} 解析；任何字节漂移都会让链上 multisend revert。
 */
class MultiSendTest {

    private static final HexFormat HEX = HexFormat.of();

    /** {@code multiSend(bytes)} 的 4 字节 selector。 */
    private static final String MULTISEND_SELECTOR = "8d80ff0a";

    private static final Address ALICE =
            Address.fromHex("0x000000000000000000000000000000000000aaaa");
    private static final Address BOB =
            Address.fromHex("0x000000000000000000000000000000000000bbbb");

    @Test
    void multisend_constant_address_polygon() {
        // 与 npm @polymarket/builder-relayer-client v0.0.6、Rust SDK 的常量必须字节级一致
        assertThat(MultiSend.MULTISEND_CALL_ONLY)
                .isEqualTo(Address.fromHex("0xA238CBeb142c10Ef7Ad8442C6D1f9E89e07e7761"));
    }

    @Test
    void encodePayload_single_tx_layout() {
        // 单笔：4 字节 calldata "0xdeadbeef"，target=ALICE
        byte[] data = HEX.parseHex("deadbeef");
        byte[] payload = MultiSend.encodePayload(List.of(new RelayerTx(ALICE, data, BigInteger.ZERO)));

        // 期望布局：1 + 20 + 32 + 32 + 4 = 89 字节
        assertThat(payload).hasSize(1 + 20 + 32 + 32 + data.length);

        String hex = HEX.formatHex(payload);
        assertThat(hex).startsWith("00");                                 // operation = 0 (Call)
        assertThat(hex.substring(2, 2 + 40)).isEqualTo(ALICE.toLowerHex().substring(2));
        // value = 0（32 字节全 0）
        assertThat(hex.substring(42, 42 + 64)).isEqualTo("00".repeat(32));
        // dataLen = 4
        assertThat(hex.substring(106, 106 + 64))
                .isEqualTo("0000000000000000000000000000000000000000000000000000000000000004");
        // data
        assertThat(hex.substring(170)).isEqualTo("deadbeef");
    }

    @Test
    void encodePayload_concatenates_multiple_txs_in_order() {
        byte[] aliceData = HEX.parseHex("aabb");
        byte[] bobData = HEX.parseHex("ccddee");
        byte[] payload = MultiSend.encodePayload(List.of(
                new RelayerTx(ALICE, aliceData, BigInteger.ZERO),
                new RelayerTx(BOB, bobData, BigInteger.ZERO)));

        // 两笔的 layout 大小：(1 + 20 + 32 + 32 + 2) + (1 + 20 + 32 + 32 + 3) = 87 + 88
        assertThat(payload).hasSize(87 + 88);

        String hex = HEX.formatHex(payload);
        // 第一笔 data 落在偏移 (1+20+32+32) * 2 = 170 字符处
        int firstDataStart = (1 + 20 + 32 + 32) * 2;
        assertThat(hex.substring(firstDataStart, firstDataStart + 4)).isEqualTo("aabb");
        // 第二笔起始 = 87 字节 = 174 字符
        assertThat(hex.substring(174, 176)).isEqualTo("00"); // 第二笔 operation
    }

    @Test
    void encodePayload_empty_list_rejected() {
        assertThatThrownBy(() -> MultiSend.encodePayload(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("txs");
    }

    @Test
    void encodeCall_starts_with_multisend_selector() {
        byte[] payload = MultiSend.encodePayload(List.of(
                new RelayerTx(ALICE, HEX.parseHex("01"), BigInteger.ZERO)));
        byte[] call = MultiSend.encodeCall(payload);

        // 头 4 字节是 multiSend(bytes) selector
        assertThat(HEX.formatHex(call)).startsWith(MULTISEND_SELECTOR);
        // 整体为 4(selector) + 32(offset) + 32(length) + payload + tail-pad
        assertThat(call.length).isGreaterThan(4 + 32 + 32 + payload.length);
    }

    @Test
    void encode_one_step_equivalent_to_two_step() {
        List<RelayerTx> txs = List.of(
                new RelayerTx(ALICE, HEX.parseHex("01"), BigInteger.ZERO),
                new RelayerTx(BOB, HEX.parseHex("02"), BigInteger.ZERO));

        byte[] oneStep = MultiSend.encode(txs);
        byte[] twoStep = MultiSend.encodeCall(MultiSend.encodePayload(txs));
        assertThat(oneStep).isEqualTo(twoStep);
    }
}
