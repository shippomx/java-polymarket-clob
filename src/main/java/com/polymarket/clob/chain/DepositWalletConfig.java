package com.polymarket.clob.chain;

import com.polymarket.clob.model.Address;

/**
 * Deposit Wallet 流的链级常量打包，用于 onboarding 与订单签名。
 * 仅 Polygon (137) 有非空配置。
 */
public record DepositWalletConfig(
        Address factory,
        Address implementation,
        Address usdcE,
        Address usdcNative,
        Address ctf,
        Address exchangeV2,
        Address negRiskExchangeV2,
        Address negRiskAdapter,
        Address pUsdQuoter,
        Address newSpenderA,
        Address newSpenderB,
        Address parlay
) {
    public static final DepositWalletConfig POLYGON = new DepositWalletConfig(
            PolymarketContracts.FACTORY,
            PolymarketContracts.IMPLEMENTATION,
            PolymarketContracts.USDC_E,
            PolymarketContracts.USDC_NATIVE,
            PolymarketContracts.CTF,
            PolymarketContracts.EXCHANGE_V2,
            PolymarketContracts.NEG_RISK_EXCHANGE_V2,
            PolymarketContracts.NEG_RISK_ADAPTER,
            PolymarketContracts.PUSD_QUOTER,
            PolymarketContracts.NEW_SPENDER_A,
            PolymarketContracts.NEW_SPENDER_B,
            PolymarketContracts.PARLAY);
}
