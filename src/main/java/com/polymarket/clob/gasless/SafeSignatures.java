package com.polymarket.clob.gasless;

import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.exception.ClobSignatureException;
import org.web3j.crypto.Hash;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Safe / SafeProxyFactory 路径下的两套 ECDSA 签名后处理。
 *
 * <p>两条路径只在 65 字节 sig 的 {@code v} 字段处理上不同：</p>
 *
 * <table>
 *   <tr><th>场景</th><th>钱包侧动作</th><th>v 输出</th><th>提交前调整</th></tr>
 *   <tr><td>SAFE_TX_PERSONAL_SIGN</td><td>{@code personal_sign(safeTxHash)}</td><td>27/28</td><td><b>v += 4</b> → 31/32</td></tr>
 *   <tr><td>SAFE_CREATE_DIRECT</td><td>{@code signTypedData_v4(... CreateProxy)}</td><td>27/28</td><td><b>不调整</b></td></tr>
 * </table>
 *
 * <p>SDK 内部的"全自动"路径（{@link GaslessRelayer#deploy} / {@link GaslessRelayer#execute}）通过
 * {@link Signer#signHash} 拿到 raw 27/28 签名后，再调用本类的方法做后处理；BE-App 协议路径里
 * App 也产 raw 27/28，BE 拿到后直接调 {@link #applyExecTransactionVTag} 转成 31/32 即可（或者
 * 让 {@link GaslessRelayer#submit} 按 {@link SafeTxPayload#style} 自动处理）。</p>
 *
 * <p>线程安全（无可变状态）。</p>
 */
public final class SafeSignatures {

    /**
     * Personal-sign 前缀：{@code "\x19Ethereum Signed Message:\n32"}（用于 32 字节摘要，长度
     * 是字面量 "32"）。
     */
    private static final byte[] PERSONAL_SIGN_PREFIX_32 =
            "\u0019Ethereum Signed Message:\n32".getBytes(StandardCharsets.UTF_8);

    private SafeSignatures() {}

    /**
     * 把 v=0/1 (yParity) 归一到 v=27/28 的标准写法；其它 v 值原样返回。
     *
     * <p>不同钱包/库输出的 {@code v} 值不一致：</p>
     * <ul>
     *   <li>web3j {@code Sign.signMessage(.., needToHash=false)} → 27/28；</li>
     *   <li>ethers.js / wagmi v5 {@code signTypedData_v4} → 27/28；</li>
     *   <li>viem 早期版本、Ledger 直签 → 0/1（yParity）；</li>
     *   <li>WalletConnect Mobile signer → 视底层钱包而定。</li>
     * </ul>
     * BE 拿到 App sig 的第一步应该是无脑过一遍本方法，再决定是否 +4。
     */
    public static byte[] normalizeV(byte[] sig65) {
        validate65(sig65);
        byte[] out = sig65.clone();
        int v = out[64] & 0xff;
        if (v == 0 || v == 1) {
            out[64] = (byte) (v + 27);
        }
        return out;
    }

    /**
     * Safe.execTransaction 路径专用 v 调整：把 27/28（personal_sign 输出）转成 31/32，让 Safe 合约
     * 走 {@code eth_sign} 校验路径。
     *
     * <p>幂等：输入若已经是 31/32 原样返回；输入若是 0/1，先 normalize 到 27/28 再 +4。</p>
     */
    public static byte[] applyExecTransactionVTag(byte[] sig65) {
        byte[] out = normalizeV(sig65);
        int v = out[64] & 0xff;
        if (v == 27 || v == 28) {
            out[64] = (byte) (v + 4);
        } else if (v != 31 && v != 32) {
            throw new ClobSignatureException(
                    "意外的 v 值 " + v + "；期望 27/28（personal_sign 后）或 31/32（已调整）");
        }
        return out;
    }

    /**
     * 本地私钥模式：包裹 {@code personal_sign} 并签名，输出 31/32 v 的 65 字节签名。
     *
     * <p>等价于 viem 端 {@code wallet.signMessage({raw: safeTxHash})} + 后续 v += 4。</p>
     */
    public static CompletableFuture<byte[]> personalSignSafeTx(Signer signer, byte[] safeTxHash) {
        Objects.requireNonNull(signer, "signer");
        Objects.requireNonNull(safeTxHash, "safeTxHash");
        if (safeTxHash.length != 32) {
            throw new IllegalArgumentException("safeTxHash 必须 32 字节");
        }
        byte[] toSign = Hash.sha3(Words.concat(PERSONAL_SIGN_PREFIX_32, safeTxHash));
        return signer.signHash(toSign).thenApply(SafeSignatures::applyExecTransactionVTag);
    }

    /**
     * 本地私钥模式：对 EIP-712 final hash <b>直接</b>做 ECDSA 签名（不包 personal_sign），
     * v 归一到 27/28。等价于 viem 端 {@code wallet.signTypedData(...)}。
     *
     * <p>用于 SAFE-CREATE 路径——SafeFactory 在 createProxy 时就是用这种"裸" EIP-712 sig 跑
     * {@code ecrecover}。</p>
     */
    public static CompletableFuture<byte[]> signEip712Direct(Signer signer, byte[] eip712Hash) {
        Objects.requireNonNull(signer, "signer");
        Objects.requireNonNull(eip712Hash, "eip712Hash");
        if (eip712Hash.length != 32) {
            throw new IllegalArgumentException("eip712Hash 必须 32 字节");
        }
        return signer.signHash(eip712Hash).thenApply(SafeSignatures::normalizeV);
    }

    private static void validate65(byte[] sig) {
        Objects.requireNonNull(sig, "sig");
        if (sig.length != 65) {
            throw new ClobSignatureException("签名长度必须 65 字节，实际 " + sig.length);
        }
    }
}
