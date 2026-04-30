package com.polymarket.clob.gasless;

import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.Hash32;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Calldata} 字节级回归。Selector 与 ABI 编码出错会让相应路径在链上 revert，所以这里
 * 用预计算的 4 字节 selector 与 docs/eip712-scenarios.md 的真实 fixture 做强校验。
 */
class CalldataTest {

    private static final HexFormat HEX = HexFormat.of();

    /** Polygon USDC.e（来自 docs/eip712-scenarios.md §0 通用约定）。 */
    private static final Address USDC =
            Address.fromHex("0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174");

    /** 普通市场撮合合约。 */
    private static final Address CTF_EXCHANGE =
            Address.fromHex("0x4bFb41d5B3570DeFd03C39a9A4D8dE6Bd8B8982E");

    /** ConditionalTokens（CTF）。 */
    private static final Address CTF =
            Address.fromHex("0x4D97DCd97eC945f40cF65F87097ACe5EA0476045");

    /** 4 字节 function selector：keccak256("approve(address,uint256)")[0:4]。 */
    private static final String APPROVE_SELECTOR = "095ea7b3";

    /** 4 字节 function selector：keccak256("setApprovalForAll(address,bool)")[0:4]。 */
    private static final String SET_APPROVAL_FOR_ALL_SELECTOR = "a22cb465";

    /** 4 字节 function selector：keccak256("transfer(address,uint256)")[0:4]。 */
    private static final String TRANSFER_SELECTOR = "a9059cbb";

    /**
     * 4 字节 function selector：keccak256(
     *   "redeemPositions(address,bytes32,bytes32,uint256[])")[0:4]。
     *
     * <p>已通过 4byte.directory（id 186551）交叉验证；与 Gnosis ConditionalTokens
     * 部署在 Polygon 上的 0x4D97DCd97eC945f40cF65F87097ACe5EA0476045 的 ABI 一致。</p>
     */
    private static final String REDEEM_POSITIONS_SELECTOR = "01b7037c";

    // =========================================================
    //  approveErc20
    // =========================================================

    @Test
    void approveErc20_max_matches_docs_fixture() {
        // 来自 docs/eip712-scenarios.md §2.1 — USDC.approve(CTFExchange, MAX_UINT256) 完整 calldata
        String expected = "0x"
                + APPROVE_SELECTOR
                + "000000000000000000000000" + CTF_EXCHANGE.toLowerHex().substring(2)
                + "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";

        byte[] data = Calldata.approveErc20(CTF_EXCHANGE);
        assertThat("0x" + HEX.formatHex(data)).isEqualToIgnoringCase(expected);
        assertThat(data).hasSize(4 + 32 + 32);
    }

    @Test
    void approveErc20_explicit_amount() {
        BigInteger one = BigInteger.ONE;
        byte[] data = Calldata.approveErc20(CTF_EXCHANGE, one);
        String hex = HEX.formatHex(data);
        assertThat(hex).startsWith(APPROVE_SELECTOR);
        // 最后 32 字节是 amount = 1
        assertThat(hex).endsWith("0000000000000000000000000000000000000000000000000000000000000001");
    }

    @Test
    void approveErc20Tx_targets_token() {
        RelayerTx tx = Calldata.approveErc20Tx(USDC, CTF_EXCHANGE);
        assertThat(tx.to()).isEqualTo(USDC);
        assertThat(tx.value()).isEqualTo(BigInteger.ZERO);
        assertThat(tx.data()).isEqualTo(Calldata.approveErc20(CTF_EXCHANGE));
    }

    @Test
    void approveErc20_rejects_negative_amount() {
        assertThatThrownBy(() -> Calldata.approveErc20(CTF_EXCHANGE, BigInteger.ONE.negate()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
    }

    // =========================================================
    //  setApprovalForAll
    // =========================================================

    @Test
    void setApprovalForAll_default_true_selector_and_layout() {
        byte[] data = Calldata.setApprovalForAll(CTF_EXCHANGE);
        String hex = HEX.formatHex(data);
        assertThat(hex).startsWith(SET_APPROVAL_FOR_ALL_SELECTOR);
        // operator 地址左 pad 到 32 字节
        assertThat(hex).contains("000000000000000000000000" + CTF_EXCHANGE.toLowerHex().substring(2));
        // approved=true 编码为最后 32 字节最低位 1
        assertThat(hex).endsWith("0000000000000000000000000000000000000000000000000000000000000001");
        assertThat(data).hasSize(4 + 32 + 32);
    }

    @Test
    void setApprovalForAll_explicit_false() {
        byte[] data = Calldata.setApprovalForAll(CTF_EXCHANGE, false);
        String hex = HEX.formatHex(data);
        assertThat(hex).endsWith("0000000000000000000000000000000000000000000000000000000000000000");
    }

    // =========================================================
    //  transferErc20
    // =========================================================

    @Test
    void transferErc20_selector_and_layout() {
        // 100 USDC.e（6 decimals）= 100_000_000
        BigInteger amount = BigInteger.valueOf(100_000_000L);
        byte[] data = Calldata.transferErc20(CTF_EXCHANGE, amount);
        String hex = HEX.formatHex(data);
        assertThat(hex).startsWith(TRANSFER_SELECTOR);
        assertThat(hex).contains("000000000000000000000000" + CTF_EXCHANGE.toLowerHex().substring(2));
        // 0x05f5e100 = 100_000_000，左 pad 到 32 字节
        assertThat(hex).endsWith("0000000000000000000000000000000000000000000000000000000005f5e100");
        assertThat(data).hasSize(4 + 32 + 32);
    }

    @Test
    void transferErc20_rejects_zero_amount() {
        // amount=0 几乎肯定是逻辑错误（不会真把 0 USDC 转出去）
        assertThatThrownBy(() -> Calldata.transferErc20(CTF_EXCHANGE, BigInteger.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void transferErc20Tx_value_zero() {
        RelayerTx tx = Calldata.transferErc20Tx(USDC, CTF_EXCHANGE, BigInteger.TEN);
        assertThat(tx.to()).isEqualTo(USDC);
        assertThat(tx.value()).isEqualTo(BigInteger.ZERO);
    }

    // =========================================================
    //  redeemPositions
    // =========================================================

    @Test
    void redeemPositions_selector_and_field_count() {
        // 二元市场 yes/no：indexSets = [1, 2]；parentCollectionId = bytes32(0)
        Hash32 parent = Hash32.fromHex("0x" + "00".repeat(32));
        Hash32 condition = Hash32.fromHex(
                "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");
        byte[] data = Calldata.redeemPositions(USDC, parent, condition,
                List.of(BigInteger.ONE, BigInteger.TWO));

        String hex = HEX.formatHex(data);
        assertThat(hex).startsWith(REDEEM_POSITIONS_SELECTOR);

        // 期望布局：selector(4) + collateral(32) + parent(32) + condition(32) + offset(32)
        //          + length(32) + idx0(32) + idx1(32)
        assertThat(data).hasSize(4 + 32 * 7);

        // collateral 地址在第 1 个 32 字节槽位
        assertThat(hex.substring(8, 8 + 64))
                .isEqualTo("000000000000000000000000" + USDC.toLowerHex().substring(2));
        // parentCollectionId 全 0
        assertThat(hex.substring(72, 72 + 64))
                .isEqualTo("00".repeat(32));
        // conditionId 第 3 个槽位
        assertThat(hex.substring(136, 136 + 64))
                .isEqualTo("1234567890abcdef".repeat(4));
    }

    @Test
    void redeemPositions_empty_index_sets_rejected() {
        Hash32 zero = Hash32.fromHex("0x" + "00".repeat(32));
        assertThatThrownBy(() -> Calldata.redeemPositions(USDC, zero, zero, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("indexSets");
    }

    @Test
    void redeemPositions_zero_index_rejected() {
        Hash32 zero = Hash32.fromHex("0x" + "00".repeat(32));
        assertThatThrownBy(() ->
                Calldata.redeemPositions(USDC, zero, zero, List.of(BigInteger.ZERO)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("indexSet");
    }

    @Test
    void redeemPositionsTx_targets_ctf() {
        Hash32 zero = Hash32.fromHex("0x" + "00".repeat(32));
        RelayerTx tx = Calldata.redeemPositionsTx(CTF, USDC, zero, zero, List.of(BigInteger.ONE));
        assertThat(tx.to()).isEqualTo(CTF);
        assertThat(tx.value()).isEqualTo(BigInteger.ZERO);
    }

    // =========================================================
    //  standardApprovalTxs
    // =========================================================

    @Test
    void standardApprovalTxs_polygon_six_txs_correct_targets() {
        List<RelayerTx> txs = Calldata.standardApprovalTxs(137);
        assertThat(txs).hasSize(6);

        // 前 3 笔：USDC.approve(...)
        assertThat(txs.get(0).to()).isEqualTo(USDC);
        assertThat(txs.get(1).to()).isEqualTo(USDC);
        assertThat(txs.get(2).to()).isEqualTo(USDC);
        // 后 3 笔：CTF.setApprovalForAll(...)
        assertThat(txs.get(3).to()).isEqualTo(CTF);
        assertThat(txs.get(4).to()).isEqualTo(CTF);
        assertThat(txs.get(5).to()).isEqualTo(CTF);

        // 前 3 笔 selector = approve
        for (int i = 0; i < 3; i++) {
            assertThat(HEX.formatHex(txs.get(i).data())).startsWith(APPROVE_SELECTOR);
        }
        // 后 3 笔 selector = setApprovalForAll
        for (int i = 3; i < 6; i++) {
            assertThat(HEX.formatHex(txs.get(i).data())).startsWith(SET_APPROVAL_FOR_ALL_SELECTOR);
        }
    }

    // =========================================================
    //  V2 授权批次（2026-04-28 CTF Exchange v2 上线后）
    // =========================================================

    @Test
    void v2OnlyApprovalTxs_polygon_four_txs_correct_targets_and_selectors() {
        List<RelayerTx> txs = Calldata.v2OnlyApprovalTxs(137);
        assertThat(txs).hasSize(4);

        // 前 2 笔：USDC.approve(V2_EXCHANGE / V2_NEG_RISK_EXCHANGE)
        assertThat(txs.get(0).to()).isEqualTo(USDC);
        assertThat(txs.get(1).to()).isEqualTo(USDC);
        assertThat(HEX.formatHex(txs.get(0).data())).startsWith(APPROVE_SELECTOR);
        assertThat(HEX.formatHex(txs.get(1).data())).startsWith(APPROVE_SELECTOR);

        // 后 2 笔：CTF.setApprovalForAll(V2_EXCHANGE / V2_NEG_RISK_EXCHANGE)
        assertThat(txs.get(2).to()).isEqualTo(CTF);
        assertThat(txs.get(3).to()).isEqualTo(CTF);
        assertThat(HEX.formatHex(txs.get(2).data())).startsWith(SET_APPROVAL_FOR_ALL_SELECTOR);
        assertThat(HEX.formatHex(txs.get(3).data())).startsWith(SET_APPROVAL_FOR_ALL_SELECTOR);
    }

    @Test
    void standardApprovalTxsV2_polygon_ten_txs_v1_then_v2() {
        List<RelayerTx> all = Calldata.standardApprovalTxsV2(137);
        assertThat(all).hasSize(10);

        // 前 6 笔与 V1 标准批次完全一致
        List<RelayerTx> v1 = Calldata.standardApprovalTxs(137);
        for (int i = 0; i < 6; i++) {
            assertThat(all.get(i).to()).isEqualTo(v1.get(i).to());
            assertThat(HEX.formatHex(all.get(i).data()))
                    .isEqualTo(HEX.formatHex(v1.get(i).data()));
        }

        // 后 4 笔与 v2OnlyApprovalTxs 完全一致
        List<RelayerTx> v2 = Calldata.v2OnlyApprovalTxs(137);
        for (int i = 0; i < 4; i++) {
            assertThat(all.get(6 + i).to()).isEqualTo(v2.get(i).to());
            assertThat(HEX.formatHex(all.get(6 + i).data()))
                    .isEqualTo(HEX.formatHex(v2.get(i).data()));
        }
    }
}
