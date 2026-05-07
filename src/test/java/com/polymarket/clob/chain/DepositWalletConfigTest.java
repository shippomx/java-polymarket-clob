package com.polymarket.clob.chain;

import com.polymarket.clob.model.ContractRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DepositWalletConfigTest {

    @Test
    void polygonReturnsConfig() {
        var cfg = ContractRegistry.depositWalletConfig(137).orElseThrow();
        assertThat(cfg.factory()).isEqualTo(PolymarketContracts.FACTORY);
        assertThat(cfg.implementation()).isEqualTo(PolymarketContracts.IMPLEMENTATION);
        assertThat(cfg.usdcE()).isEqualTo(PolymarketContracts.USDC_E);
        assertThat(cfg.usdcNative()).isEqualTo(PolymarketContracts.USDC_NATIVE);
        assertThat(cfg.ctf()).isEqualTo(PolymarketContracts.CTF);
        assertThat(cfg.exchangeV2()).isEqualTo(PolymarketContracts.EXCHANGE_V2);
        assertThat(cfg.negRiskExchangeV2()).isEqualTo(PolymarketContracts.NEG_RISK_EXCHANGE_V2);
        assertThat(cfg.negRiskAdapter()).isEqualTo(PolymarketContracts.NEG_RISK_ADAPTER);
        assertThat(cfg.pUsdQuoter()).isEqualTo(PolymarketContracts.PUSD_QUOTER);
        assertThat(cfg.newSpenderA()).isEqualTo(PolymarketContracts.NEW_SPENDER_A);
        assertThat(cfg.newSpenderB()).isEqualTo(PolymarketContracts.NEW_SPENDER_B);
        assertThat(cfg.parlay()).isEqualTo(PolymarketContracts.PARLAY);
    }

    @Test
    void amoyReturnsEmpty() {
        assertThat(ContractRegistry.depositWalletConfig(80002)).isEmpty();
    }

    @Test
    void unknownChainReturnsEmpty() {
        assertThat(ContractRegistry.depositWalletConfig(1)).isEmpty();
    }
}
