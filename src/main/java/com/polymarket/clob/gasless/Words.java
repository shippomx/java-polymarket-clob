package com.polymarket.clob.gasless;

import com.polymarket.clob.model.Address;

import java.math.BigInteger;

/**
 * 32 字节 EVM word 操作的 package-private 工具：手工 ABI 编码场景下的最小集，
 * 服务于 {@link SafeEip712} / {@link MultiSend} 等需要 keccak / left-pad / concat 的地方。
 *
 * <p>之所以不用 web3j {@code TypeEncoder}：</p>
 * <ul>
 *   <li>SafeTx v1.3.0 的 EIP-712 domain 没有 {@code name}，与 web3j {@code StructuredDataEncoder}
 *       默认假设不一致；</li>
 *   <li>multisend payload 不是普通的 ABI tuple 编码，是手工拼接的 packed bytes。</li>
 * </ul>
 * 直接按字节落实 EIP-712 / EIP-2020 规范更可控、更易对齐 Rust 实现。
 */
final class Words {

    private Words() {}

    /** 32 字节 left-pad；{@code BigInteger.toByteArray()} 多出的 0 头会被剥掉。 */
    static byte[] leftPad32(BigInteger v) {
        byte[] raw = v.signum() == 0 ? new byte[]{0} : v.toByteArray();
        int start = (raw.length > 0 && raw[0] == 0 && raw.length > 1) ? 1 : 0;
        int len = raw.length - start;
        if (len > 32) {
            throw new IllegalArgumentException("uint256 溢出");
        }
        byte[] out = new byte[32];
        System.arraycopy(raw, start, out, 32 - len, len);
        return out;
    }

    /** 32 字节 left-pad：地址（20 字节）→ 32 字节 word。 */
    static byte[] leftPad32(Address a) {
        byte[] out = new byte[32];
        System.arraycopy(a.toBytes(), 0, out, 12, 20);
        return out;
    }

    /** 顺序拼接多段字节。 */
    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }
}
