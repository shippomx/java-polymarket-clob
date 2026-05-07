package com.polymarket.clob.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContractRegistryTest {

    @Test
    void polygonNonNegRisk() {
        ContractConfig cfg = ContractRegistry.contractConfig(ChainId.POLYGON, false).orElseThrow();
        assertThat(cfg.exchange())
                .isEqualTo(Address.fromHex("0x4bFb41d5B3570DeFd03C39a9A4D8dE6Bd8B8982E"));
        assertThat(cfg.collateral())
                .isEqualTo(Address.fromHex("0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174"));
        assertThat(cfg.conditionalTokens())
                .isEqualTo(Address.fromHex("0x4D97DCd97eC945f40cF65F87097ACe5EA0476045"));
        assertThat(cfg.negRiskAdapter()).isEmpty();
    }

    @Test
    void polygonNegRisk() {
        ContractConfig cfg = ContractRegistry.contractConfig(ChainId.POLYGON, true).orElseThrow();
        assertThat(cfg.exchange())
                .isEqualTo(Address.fromHex("0xC5d563A36AE78145C45a50134d48A1215220f80a"));
        assertThat(cfg.negRiskAdapter()).contains(
                Address.fromHex("0xd91E80cF2E7be2e162c6513ceD06f1dD0dA35296"));
    }

    @Test
    void amoyNonNegRisk() {
        ContractConfig cfg = ContractRegistry.contractConfig(ChainId.AMOY, false).orElseThrow();
        assertThat(cfg.exchange())
                .isEqualTo(Address.fromHex("0xdFE02Eb6733538f8Ea35D585af8DE5958AD99E40"));
        assertThat(cfg.collateral())
                .isEqualTo(Address.fromHex("0x9c4e1703476e875070ee25b56a58b008cfb8fa78"));
        assertThat(cfg.conditionalTokens())
                .isEqualTo(Address.fromHex("0x69308FB512518e39F9b16112fA8d994F4e2Bf8bB"));
        assertThat(cfg.negRiskAdapter()).isEmpty();
    }

    @Test
    void amoyNegRisk() {
        ContractConfig cfg = ContractRegistry.contractConfig(ChainId.AMOY, true).orElseThrow();
        // Amoy NEG_RISK exchange 与 adapter 指向同一地址，是 Rust 源码的有意设计
        assertThat(cfg.exchange())
                .isEqualTo(Address.fromHex("0xd91E80cF2E7be2e162c6513ceD06f1dD0dA35296"));
        assertThat(cfg.collateral())
                .isEqualTo(Address.fromHex("0x9c4e1703476e875070ee25b56a58b008cfb8fa78"));
        assertThat(cfg.conditionalTokens())
                .isEqualTo(Address.fromHex("0x69308FB512518e39F9b16112fA8d994F4e2Bf8bB"));
        assertThat(cfg.negRiskAdapter()).contains(
                Address.fromHex("0xd91E80cF2E7be2e162c6513ceD06f1dD0dA35296"));
    }

    @Test
    void unsupportedChainReturnsEmpty() {
        assertThat(ContractRegistry.contractConfig(1L, false)).isEmpty();
        assertThat(ContractRegistry.contractConfig(1L, true)).isEmpty();
    }

    // -------------------- V2 矩阵（2026-04-28 CTF Exchange v1→v2 切换）--------------------

    @Test
    void polygonNonNegRiskHasV2Addresses() {
        ContractConfig cfg = ContractRegistry.contractConfig(ChainId.POLYGON, false).orElseThrow();
        assertThat(cfg.exchangeV2()).contains(
                Address.fromHex("0xE111180000d2663C0091e4f400237545B87B996B"));
        assertThat(cfg.negRiskExchangeV2()).contains(
                Address.fromHex("0xe2222d279d744050d28e00520010520000310F59"));
        assertThat(cfg.pUsd()).contains(
                Address.fromHex("0xC011a7E12a19f7B1f670d46F03B03f3342E82DFB"));
    }

    @Test
    void polygonNegRiskHasV2Addresses() {
        ContractConfig cfg = ContractRegistry.contractConfig(ChainId.POLYGON, true).orElseThrow();
        assertThat(cfg.exchangeV2()).contains(
                Address.fromHex("0xE111180000d2663C0091e4f400237545B87B996B"));
        assertThat(cfg.negRiskExchangeV2()).contains(
                Address.fromHex("0xe2222d279d744050d28e00520010520000310F59"));
    }

    @Test
    void exchangeV2Helper() {
        // negRisk=false 选 exchangeV2
        assertThat(ContractRegistry.exchangeV2(ChainId.POLYGON, false)).contains(
                Address.fromHex("0xE111180000d2663C0091e4f400237545B87B996B"));
        // negRisk=true 选 negRiskExchangeV2
        assertThat(ContractRegistry.exchangeV2(ChainId.POLYGON, true)).contains(
                Address.fromHex("0xe2222d279d744050d28e00520010520000310F59"));
        // 未注册链返回 empty
        assertThat(ContractRegistry.exchangeV2(1L, false)).isEmpty();
    }
}
