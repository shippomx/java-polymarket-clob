package com.polymarket.clob.gasless;

import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SafeEip712} EIP-712 摘要回归。
 *
 * <p>没有上游 byte-level fixture 时（npm builder-relayer-client v0.0.6 不公开测试向量），
 * 用以下三类自一致性约束兜住回归：</p>
 * <ol>
 *   <li>同输入永远产同输出（typeHash 缓存正确）；</li>
 *   <li>每一个输入维度变化（chainId / safe / to / data / operation / nonce）都会改变输出（无字段
 *       漏传 / 字节顺序错乱）；</li>
 *   <li>32 字节长度（keccak256 输出）。</li>
 * </ol>
 */
class SafeEip712Test {

    private static final long CHAIN_ID = 137;

    private static final Address SAFE =
            Address.fromHex("0x82f55b4bD815FeAEc6E92469c7788Da4E9685D0A");
    private static final Address SAFE_FACTORY =
            Address.fromHex("0xaacFeEa03eb1561C4e67d661e40682Bd20E3541b");
    private static final Address USDC =
            Address.fromHex("0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174");
    private static final Address CTF_EXCHANGE =
            Address.fromHex("0x4bFb41d5B3570DeFd03C39a9A4D8dE6Bd8B8982E");

    private static final byte[] DUMMY_DATA = new byte[]{(byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef};

    // =========================================================
    //  safeTxHash
    // =========================================================

    @Test
    void safeTxHash_returns_32_bytes() {
        byte[] hash = SafeEip712.safeTxHash(CHAIN_ID, SAFE, USDC, DUMMY_DATA, 0, BigInteger.ZERO);
        assertThat(hash).hasSize(32);
    }

    @Test
    void safeTxHash_is_deterministic_for_same_input() {
        byte[] a = SafeEip712.safeTxHash(CHAIN_ID, SAFE, USDC, DUMMY_DATA, 0, BigInteger.ZERO);
        byte[] b = SafeEip712.safeTxHash(CHAIN_ID, SAFE, USDC, DUMMY_DATA, 0, BigInteger.ZERO);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void safeTxHash_changes_when_chainId_changes() {
        byte[] polygon = SafeEip712.safeTxHash(137, SAFE, USDC, DUMMY_DATA, 0, BigInteger.ZERO);
        byte[] amoy = SafeEip712.safeTxHash(80002, SAFE, USDC, DUMMY_DATA, 0, BigInteger.ZERO);
        assertThat(polygon).isNotEqualTo(amoy);
    }

    @Test
    void safeTxHash_changes_when_safe_changes() {
        byte[] a = SafeEip712.safeTxHash(CHAIN_ID, SAFE, USDC, DUMMY_DATA, 0, BigInteger.ZERO);
        byte[] b = SafeEip712.safeTxHash(CHAIN_ID, SAFE_FACTORY, USDC, DUMMY_DATA, 0, BigInteger.ZERO);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void safeTxHash_changes_when_to_changes() {
        byte[] a = SafeEip712.safeTxHash(CHAIN_ID, SAFE, USDC, DUMMY_DATA, 0, BigInteger.ZERO);
        byte[] b = SafeEip712.safeTxHash(CHAIN_ID, SAFE, CTF_EXCHANGE, DUMMY_DATA, 0, BigInteger.ZERO);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void safeTxHash_changes_when_data_changes() {
        byte[] a = SafeEip712.safeTxHash(CHAIN_ID, SAFE, USDC, DUMMY_DATA, 0, BigInteger.ZERO);
        byte[] b = SafeEip712.safeTxHash(CHAIN_ID, SAFE, USDC, new byte[]{0x00}, 0, BigInteger.ZERO);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void safeTxHash_changes_when_operation_changes() {
        byte[] call = SafeEip712.safeTxHash(CHAIN_ID, SAFE, USDC, DUMMY_DATA, 0, BigInteger.ZERO);
        byte[] delegateCall = SafeEip712.safeTxHash(CHAIN_ID, SAFE, USDC, DUMMY_DATA, 1, BigInteger.ZERO);
        assertThat(call).isNotEqualTo(delegateCall);
    }

    @Test
    void safeTxHash_changes_when_nonce_changes() {
        byte[] a = SafeEip712.safeTxHash(CHAIN_ID, SAFE, USDC, DUMMY_DATA, 0, BigInteger.ZERO);
        byte[] b = SafeEip712.safeTxHash(CHAIN_ID, SAFE, USDC, DUMMY_DATA, 0, BigInteger.ONE);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void safeTxHash_rejects_invalid_operation() {
        assertThatThrownBy(() ->
                SafeEip712.safeTxHash(CHAIN_ID, SAFE, USDC, DUMMY_DATA, 2, BigInteger.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operation");
    }

    @Test
    void safeTxHash_rejects_non_positive_chain_id() {
        assertThatThrownBy(() ->
                SafeEip712.safeTxHash(0, SAFE, USDC, DUMMY_DATA, 0, BigInteger.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chainId");
    }

    @Test
    void safeTxHash_rejects_negative_nonce() {
        assertThatThrownBy(() ->
                SafeEip712.safeTxHash(CHAIN_ID, SAFE, USDC, DUMMY_DATA, 0, BigInteger.ONE.negate()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonce");
    }

    // =========================================================
    //  createProxyHash
    // =========================================================

    @Test
    void createProxyHash_returns_32_bytes() {
        byte[] hash = SafeEip712.createProxyHash(CHAIN_ID, SAFE_FACTORY);
        assertThat(hash).hasSize(32);
    }

    @Test
    void createProxyHash_is_deterministic() {
        byte[] a = SafeEip712.createProxyHash(CHAIN_ID, SAFE_FACTORY);
        byte[] b = SafeEip712.createProxyHash(CHAIN_ID, SAFE_FACTORY);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void createProxyHash_changes_when_chainId_changes() {
        byte[] polygon = SafeEip712.createProxyHash(137, SAFE_FACTORY);
        byte[] amoy = SafeEip712.createProxyHash(80002, SAFE_FACTORY);
        assertThat(polygon).isNotEqualTo(amoy);
    }

    @Test
    void createProxyHash_changes_when_factory_changes() {
        byte[] a = SafeEip712.createProxyHash(CHAIN_ID, SAFE_FACTORY);
        byte[] b = SafeEip712.createProxyHash(CHAIN_ID, SAFE);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void createProxyHash_differs_from_safeTxHash() {
        // 即便 chainId / verifyingContract 取相同地址，两条 712 路径的 typeHash 不同 → 摘要不同
        byte[] proxy = SafeEip712.createProxyHash(CHAIN_ID, SAFE_FACTORY);
        byte[] safeTx = SafeEip712.safeTxHash(CHAIN_ID, SAFE_FACTORY, Address.ZERO,
                new byte[0], 0, BigInteger.ZERO);
        assertThat(proxy).isNotEqualTo(safeTx);
    }
}
