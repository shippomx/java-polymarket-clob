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
