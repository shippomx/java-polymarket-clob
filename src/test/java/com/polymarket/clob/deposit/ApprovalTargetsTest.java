package com.polymarket.clob.deposit;

import com.polymarket.clob.chain.DepositWalletConfig;
import com.polymarket.clob.chain.PolymarketContracts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalTargetsTest {

    @Test
    void thirteenStandardTargetsInSpecOrder() {
        DepositWalletConfig cfg = DepositWalletConfig.POLYGON;
        List<ApprovalTargets.Entry> targets = ApprovalTargets.standard(cfg);

        assertThat(targets).hasSize(13);

        assertThat(targets.get(0)).isEqualTo(new ApprovalTargets.Entry(
                cfg.usdcE(), ApprovalTargets.Kind.ERC20, cfg.ctf()));
        assertThat(targets.get(1)).isEqualTo(new ApprovalTargets.Entry(
                cfg.usdcE(), ApprovalTargets.Kind.ERC20, cfg.exchangeV2()));
        assertThat(targets.get(6)).isEqualTo(new ApprovalTargets.Entry(
                cfg.usdcNative(), ApprovalTargets.Kind.ERC20, cfg.pUsdQuoter()));
        assertThat(targets.get(7)).isEqualTo(new ApprovalTargets.Entry(
                cfg.ctf(), ApprovalTargets.Kind.CTF, cfg.exchangeV2()));
        assertThat(targets.get(12)).isEqualTo(new ApprovalTargets.Entry(
                cfg.ctf(), ApprovalTargets.Kind.CTF, cfg.parlay()));

        // 全 13 项 token 顺序与 spec §6.2 对齐
        assertThat(targets.subList(0, 7)).allMatch(e -> e.kind() == ApprovalTargets.Kind.ERC20);
        assertThat(targets.subList(7, 13)).allMatch(e -> e.kind() == ApprovalTargets.Kind.CTF);
    }
}
