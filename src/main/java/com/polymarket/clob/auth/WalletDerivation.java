package com.polymarket.clob.auth;

import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.ContractRegistry;
import com.polymarket.clob.model.WalletContractConfig;
import org.web3j.crypto.Hash;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * 从 EOA 派生 Polymarket Proxy / Gnosis Safe 智能钱包地址（CREATE2）。
 *
 * <p>与 Rust {@code rs-clob-client/src/lib.rs} 中的 {@code derive_proxy_wallet}
 * / {@code derive_safe_wallet} 一一对应；两组 init-code-hash 常量和 Rust 完全一致，
 * 测试里以 Anvil 账户 {@code 0xf39F...2266} 作 Polygon 上的 fixture 交叉验证。</p>
 *
 * <p>盐值生成规则不同：Proxy 用 {@code keccak256(eoa_bytes)}（20 字节无填充），
 * Safe 用 {@code keccak256(leftPad32(eoa_bytes))}（左填充到 32 字节，和 ABI 编码一致）。</p>
 */
public final class WalletDerivation {

    /** Init code hash for Polymarket Proxy wallets (EIP-1167 minimal proxy). */
    private static final byte[] PROXY_INIT_CODE_HASH = hex32(
            "d21df8dc65880a8606f09fe0ce3df9b8869287ab0b058be05aa9e8af6330a00b");

    /** Init code hash for Gnosis Safe wallets. */
    private static final byte[] SAFE_INIT_CODE_HASH = hex32(
            "2bce2127ff07fb632d16c8347c4ebf501f4841168bed00d9e6ef715ddb6fcecf");

    private WalletDerivation() {}

    /**
     * 派生指定链上该 EOA 的 Polymarket Proxy 钱包地址。
     * Amoy 未部署 Proxy 工厂，返回 {@link Optional#empty()}。
     */
    public static Optional<Address> deriveProxyWallet(Address eoa, long chainId) {
        Optional<WalletContractConfig> cfg = ContractRegistry.walletConfig(chainId);
        if (cfg.isEmpty() || cfg.get().proxyFactory().isEmpty()) {
            return Optional.empty();
        }
        Address factory = cfg.get().proxyFactory().get();
        byte[] salt = Hash.sha3(eoa.toBytes());
        return Optional.of(create2(factory, salt, PROXY_INIT_CODE_HASH));
    }

    /**
     * 派生指定链上该 EOA 的 1-of-1 Gnosis Safe 钱包地址。
     * Polygon / Amoy 用同一套 Safe 工厂，故两链派生结果一致。
     */
    public static Optional<Address> deriveSafeWallet(Address eoa, long chainId) {
        Optional<WalletContractConfig> cfg = ContractRegistry.walletConfig(chainId);
        if (cfg.isEmpty()) return Optional.empty();
        Address factory = cfg.get().safeFactory();
        byte[] padded = new byte[32];
        System.arraycopy(eoa.toBytes(), 0, padded, 12, 20);
        byte[] salt = Hash.sha3(padded);
        return Optional.of(create2(factory, salt, SAFE_INIT_CODE_HASH));
    }

    /**
     * 按 {@link SignatureType} 选择 funder：EOA 直接返回自身；POLY_PROXY / POLY_GNOSIS_SAFE
     * 派生失败时返回空（上层应报错）。
     *
     * <p>{@link SignatureType#POLY_1271} 由调用方自行提供 funder（合约钱包地址通常通过
     * 链上注册而非 CREATE2 派生），本工具不试图猜测，返回 {@link Optional#empty()}。</p>
     */
    public static Optional<Address> deriveFunder(SignatureType type, Address eoa, long chainId) {
        return switch (type) {
            case EOA -> Optional.of(eoa);
            case POLY_PROXY -> deriveProxyWallet(eoa, chainId);
            case POLY_GNOSIS_SAFE -> deriveSafeWallet(eoa, chainId);
            case POLY_1271 -> Optional.empty();
        };
    }

    /**
     * {@code address = keccak256(0xff || factory || salt || initCodeHash)[12..]}
     * —— EIP-1014 定义的 CREATE2 地址派生。
     */
    private static Address create2(Address factory, byte[] salt, byte[] initCodeHash) {
        ByteBuffer buf = ByteBuffer.allocate(1 + 20 + 32 + 32);
        buf.put((byte) 0xff);
        buf.put(factory.toBytes());
        buf.put(salt);
        buf.put(initCodeHash);
        byte[] digest = Hash.sha3(buf.array());
        byte[] addr = new byte[20];
        System.arraycopy(digest, 12, addr, 0, 20);
        return Address.fromBytes(addr);
    }

    private static byte[] hex32(String hex) {
        byte[] out = java.util.HexFormat.of().parseHex(hex);
        if (out.length != 32) throw new IllegalStateException("expected 32 bytes, got " + out.length);
        return out;
    }
}
