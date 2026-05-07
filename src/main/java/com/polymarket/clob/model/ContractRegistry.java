package com.polymarket.clob.model;

import java.util.Map;
import java.util.Optional;

import com.polymarket.clob.chain.DepositWalletConfig;

/**
 * 合约地址注册表。最早对齐 Rust {@code rs-clob-client v0.4.4}（V1 only），
 * 2026-04-28 V2 上线后扩展为「V1 + V2」共存矩阵：
 *
 * <ul>
 *   <li>{@link #contractConfig(long, boolean)}：返回包含 V1 + V2 字段的 {@link ContractConfig}；
 *       V1 字段总在，V2 字段在已部署的链（Polygon 137 / Amoy 80002）上同样在。</li>
 *   <li>{@link #walletConfig(long)}：钱包工厂地址（Proxy / Gnosis Safe）；与 V2 切换无关。</li>
 * </ul>
 *
 * <p>V2 地址来源：rs-clob-client-v2 与 py-clob-client-v2 的 {@code config}
 * （Polygon 与 Amoy 共享同一组 V2 Exchange，因为 V2 Exchange 是同一份合约部署到多链）。</p>
 *
 * <p>注意：{@code collateral} 字段保留为 USDC.e（Polygon: {@code 0x2791..4174}），与 rs-clob-client-v2
 * 一致；V2 引入的 pUSD wrapper（{@code 0xC011..DFB}）走单独的 {@code pUsd} 字段，调用方按需取用。</p>
 */
public final class ContractRegistry {

    // ---------- V1 only 兼容地址（保留给历史测试与 parity-oracle） ----------
    private static final Address USDC_E_POLYGON =
            Address.fromHex("0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174");
    private static final Address USDC_E_AMOY =
            Address.fromHex("0x9c4e1703476e875070ee25b56a58b008cfb8fa78");
    private static final Address CTF_POLYGON =
            Address.fromHex("0x4D97DCd97eC945f40cF65F87097ACe5EA0476045");
    private static final Address CTF_AMOY =
            Address.fromHex("0x69308FB512518e39F9b16112fA8d994F4e2Bf8bB");
    private static final Address NEG_RISK_ADAPTER =
            Address.fromHex("0xd91E80cF2E7be2e162c6513ceD06f1dD0dA35296");

    // ---------- V1 Exchange ----------
    private static final Address V1_EXCHANGE_POLYGON =
            Address.fromHex("0x4bFb41d5B3570DeFd03C39a9A4D8dE6Bd8B8982E");
    private static final Address V1_EXCHANGE_AMOY =
            Address.fromHex("0xdFE02Eb6733538f8Ea35D585af8DE5958AD99E40");
    private static final Address V1_NEG_RISK_EXCHANGE_POLYGON =
            Address.fromHex("0xC5d563A36AE78145C45a50134d48A1215220f80a");

    // ---------- V2 Exchange（Polygon 与 Amoy 共用同一份部署） ----------
    private static final Address V2_EXCHANGE =
            Address.fromHex("0xE111180000d2663C0091e4f400237545B87B996B");
    private static final Address V2_NEG_RISK_EXCHANGE =
            Address.fromHex("0xe2222d279d744050d28e00520010520000310F59");

    // ---------- V2 collateral wrapper (pUSD)：USDC/USDC.e 1:1 wrap，6 decimals ----------
    private static final Address P_USD_POLYGON =
            Address.fromHex("0xC011a7E12a19f7B1f670d46F03B03f3342E82DFB");

    private static final Map<Long, ContractConfig> STANDARD = Map.of(
            ChainId.POLYGON, ContractConfig.ofV2(
                    V1_EXCHANGE_POLYGON,
                    USDC_E_POLYGON,
                    CTF_POLYGON,
                    Optional.empty(),
                    V2_EXCHANGE,
                    V2_NEG_RISK_EXCHANGE,
                    P_USD_POLYGON),
            ChainId.AMOY, ContractConfig.ofV2(
                    V1_EXCHANGE_AMOY,
                    USDC_E_AMOY,
                    CTF_AMOY,
                    Optional.empty(),
                    V2_EXCHANGE,
                    V2_NEG_RISK_EXCHANGE,
                    P_USD_POLYGON));

    private static final Map<Long, ContractConfig> NEG_RISK = Map.of(
            ChainId.POLYGON, ContractConfig.ofV2(
                    V1_NEG_RISK_EXCHANGE_POLYGON,
                    USDC_E_POLYGON,
                    CTF_POLYGON,
                    Optional.of(NEG_RISK_ADAPTER),
                    V2_EXCHANGE,
                    V2_NEG_RISK_EXCHANGE,
                    P_USD_POLYGON),
            ChainId.AMOY, ContractConfig.ofV2(
                    NEG_RISK_ADAPTER,
                    USDC_E_AMOY,
                    CTF_AMOY,
                    Optional.of(NEG_RISK_ADAPTER),
                    V2_EXCHANGE,
                    V2_NEG_RISK_EXCHANGE,
                    P_USD_POLYGON));

    private static final Map<Long, WalletContractConfig> WALLETS = Map.of(
            ChainId.POLYGON, WalletContractConfig.of(
                    Optional.of(Address.fromHex("0xaB45c5A4B0c941a2F231C04C3f49182e1A254052")),
                    Address.fromHex("0xaacFeEa03eb1561C4e67d661e40682Bd20E3541b")),
            ChainId.AMOY, WalletContractConfig.of(
                    Optional.empty(),
                    Address.fromHex("0xaacFeEa03eb1561C4e67d661e40682Bd20E3541b")));

    private ContractRegistry() {}

    public static Optional<ContractConfig> contractConfig(long chainId, boolean negRisk) {
        Map<Long, ContractConfig> table = negRisk ? NEG_RISK : STANDARD;
        return Optional.ofNullable(table.get(chainId));
    }

    public static Optional<WalletContractConfig> walletConfig(long chainId) {
        return Optional.ofNullable(WALLETS.get(chainId));
    }

    /**
     * 按 V2 选择 {@code verifyingContract}：{@code negRisk=true → negRiskExchangeV2}，否则
     * {@code exchangeV2}。返回 {@link Optional#empty()} 表示该链没有 V2 部署。
     *
     * <p>V2 EIP-712 签名仅依赖 {@code chainId} + {@code negRisk} 这两个开关，因此本方法是
     * {@link com.polymarket.clob.order.EIP712OrderSigner} V2 路径的唯一入口。</p>
     */
    public static Optional<Address> exchangeV2(long chainId, boolean negRisk) {
        return contractConfig(chainId, negRisk)
                .flatMap(cfg -> negRisk ? cfg.negRiskExchangeV2() : cfg.exchangeV2());
    }

    /**
     * 返回指定链的 Deposit Wallet 配置。仅 Polygon 137 有部署；其它链一律返回空。
     */
    public static Optional<DepositWalletConfig> depositWalletConfig(long chainId) {
        return chainId == ChainId.POLYGON ? Optional.of(DepositWalletConfig.POLYGON) : Optional.empty();
    }
}
