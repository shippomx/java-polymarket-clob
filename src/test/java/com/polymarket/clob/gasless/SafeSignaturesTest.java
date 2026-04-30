package com.polymarket.clob.gasless;

import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.exception.ClobSignatureException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SafeSignatures} v 调整与签名包裹回归。
 *
 * <p>本测试是上线前唯一不可省的回归：v 字段错一个值就会让 ecrecover 校验失败而 wire JSON 长度合法
 * （132 字符），是经典的"沉默错误"。</p>
 */
class SafeSignaturesTest {

    /** 与 {@code EIP712OrderSignerTest} 共享的公开测试私钥（py-order-utils baseline）。 */
    private static final String PRIVATE_KEY =
            "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";

    private static byte[] sigWithV(int v) {
        byte[] sig = new byte[65];
        // r 与 s 任意取（v 调整不依赖它们）
        for (int i = 0; i < 64; i++) {
            sig[i] = (byte) i;
        }
        sig[64] = (byte) v;
        return sig;
    }

    // =========================================================
    //  normalizeV
    // =========================================================

    @Test
    void normalizeV_lifts_yparity_zero_to_27() {
        byte[] out = SafeSignatures.normalizeV(sigWithV(0x00));
        assertThat(out[64] & 0xff).isEqualTo(27);
    }

    @Test
    void normalizeV_lifts_yparity_one_to_28() {
        byte[] out = SafeSignatures.normalizeV(sigWithV(0x01));
        assertThat(out[64] & 0xff).isEqualTo(28);
    }

    @Test
    void normalizeV_passes_27_through() {
        byte[] out = SafeSignatures.normalizeV(sigWithV(27));
        assertThat(out[64] & 0xff).isEqualTo(27);
    }

    @Test
    void normalizeV_passes_28_through() {
        byte[] out = SafeSignatures.normalizeV(sigWithV(28));
        assertThat(out[64] & 0xff).isEqualTo(28);
    }

    @Test
    void normalizeV_does_not_mutate_input() {
        byte[] input = sigWithV(0x00);
        byte[] copy = input.clone();
        SafeSignatures.normalizeV(input);
        assertThat(input).isEqualTo(copy);
    }

    @Test
    void normalizeV_rejects_wrong_length() {
        assertThatThrownBy(() -> SafeSignatures.normalizeV(new byte[64]))
                .isInstanceOf(ClobSignatureException.class)
                .hasMessageContaining("65");
    }

    // =========================================================
    //  applyExecTransactionVTag
    // =========================================================

    @Test
    void applyExecTransactionVTag_lifts_27_to_31() {
        byte[] out = SafeSignatures.applyExecTransactionVTag(sigWithV(27));
        assertThat(out[64] & 0xff).isEqualTo(31);
    }

    @Test
    void applyExecTransactionVTag_lifts_28_to_32() {
        byte[] out = SafeSignatures.applyExecTransactionVTag(sigWithV(28));
        assertThat(out[64] & 0xff).isEqualTo(32);
    }

    @Test
    void applyExecTransactionVTag_is_idempotent_at_31() {
        byte[] out = SafeSignatures.applyExecTransactionVTag(sigWithV(31));
        assertThat(out[64] & 0xff).isEqualTo(31);
    }

    @Test
    void applyExecTransactionVTag_is_idempotent_at_32() {
        byte[] out = SafeSignatures.applyExecTransactionVTag(sigWithV(32));
        assertThat(out[64] & 0xff).isEqualTo(32);
    }

    @Test
    void applyExecTransactionVTag_handles_yparity_zero() {
        // 0 → normalize → 27 → +4 → 31
        byte[] out = SafeSignatures.applyExecTransactionVTag(sigWithV(0));
        assertThat(out[64] & 0xff).isEqualTo(31);
    }

    @Test
    void applyExecTransactionVTag_handles_yparity_one() {
        byte[] out = SafeSignatures.applyExecTransactionVTag(sigWithV(1));
        assertThat(out[64] & 0xff).isEqualTo(32);
    }

    @Test
    void applyExecTransactionVTag_rejects_unexpected_v() {
        // 比如 v=37（EIP-155 编码）— 这条路径不允许
        assertThatThrownBy(() -> SafeSignatures.applyExecTransactionVTag(sigWithV(37)))
                .isInstanceOf(ClobSignatureException.class);
    }

    @Test
    void applyExecTransactionVTag_does_not_mutate_input() {
        byte[] input = sigWithV(27);
        byte[] copy = input.clone();
        SafeSignatures.applyExecTransactionVTag(input);
        assertThat(input).isEqualTo(copy);
    }

    // =========================================================
    //  本地私钥签名 + 包裹路径（与 LocalSigner 集成）
    // =========================================================

    @Test
    void personalSignSafeTx_outputs_v_31_or_32() throws Exception {
        LocalSigner signer = LocalSigner.fromPrivateKey(PRIVATE_KEY);
        byte[] safeTxHash = new byte[32]; // 任意 32 字节摘要
        for (int i = 0; i < 32; i++) {
            safeTxHash[i] = (byte) (i * 7);
        }
        byte[] sig = SafeSignatures.personalSignSafeTx(signer, safeTxHash).get();
        assertThat(sig).hasSize(65);
        int v = sig[64] & 0xff;
        assertThat(v).isIn(31, 32);
    }

    @Test
    void signEip712Direct_outputs_v_27_or_28() throws Exception {
        LocalSigner signer = LocalSigner.fromPrivateKey(PRIVATE_KEY);
        byte[] eip712Hash = new byte[32];
        for (int i = 0; i < 32; i++) {
            eip712Hash[i] = (byte) (i * 13);
        }
        byte[] sig = SafeSignatures.signEip712Direct(signer, eip712Hash).get();
        assertThat(sig).hasSize(65);
        int v = sig[64] & 0xff;
        assertThat(v).isIn(27, 28);
    }

    @Test
    void personalSignSafeTx_is_deterministic_for_same_signer_and_hash() throws Exception {
        LocalSigner signer = LocalSigner.fromPrivateKey(PRIVATE_KEY);
        byte[] safeTxHash = new byte[32];
        byte[] a = SafeSignatures.personalSignSafeTx(signer, safeTxHash).get();
        byte[] b = SafeSignatures.personalSignSafeTx(signer, safeTxHash).get();
        assertThat(a).isEqualTo(b);
    }

    @Test
    void personalSignSafeTx_rejects_wrong_hash_length() {
        LocalSigner signer = LocalSigner.fromPrivateKey(PRIVATE_KEY);
        assertThatThrownBy(() -> SafeSignatures.personalSignSafeTx(signer, new byte[31]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void signEip712Direct_rejects_wrong_hash_length() {
        LocalSigner signer = LocalSigner.fromPrivateKey(PRIVATE_KEY);
        assertThatThrownBy(() -> SafeSignatures.signEip712Direct(signer, new byte[33]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
