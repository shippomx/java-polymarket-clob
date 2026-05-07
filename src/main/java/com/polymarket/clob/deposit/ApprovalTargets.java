package com.polymarket.clob.deposit;

import com.polymarket.clob.chain.DepositWalletConfig;
import com.polymarket.clob.model.Address;

import java.util.List;

/**
 * Onboarding 标准 13 项授权目标。顺序锁死，与 fullOnboardAndTrade.ts 一致。
 */
public final class ApprovalTargets {

    public enum Kind { ERC20, CTF }

    public record Entry(Address token, Kind kind, Address spender) {}

    private ApprovalTargets() {}

    public static List<Entry> standard(DepositWalletConfig cfg) {
        return List.of(
                new Entry(cfg.usdcE(),       Kind.ERC20, cfg.ctf()),
                new Entry(cfg.usdcE(),       Kind.ERC20, cfg.exchangeV2()),
                new Entry(cfg.usdcE(),       Kind.ERC20, cfg.negRiskExchangeV2()),
                new Entry(cfg.usdcE(),       Kind.ERC20, cfg.negRiskAdapter()),
                new Entry(cfg.usdcE(),       Kind.ERC20, cfg.newSpenderA()),
                new Entry(cfg.usdcE(),       Kind.ERC20, cfg.newSpenderB()),
                new Entry(cfg.usdcNative(),  Kind.ERC20, cfg.pUsdQuoter()),
                new Entry(cfg.ctf(),         Kind.CTF,   cfg.exchangeV2()),
                new Entry(cfg.ctf(),         Kind.CTF,   cfg.negRiskExchangeV2()),
                new Entry(cfg.ctf(),         Kind.CTF,   cfg.negRiskAdapter()),
                new Entry(cfg.ctf(),         Kind.CTF,   cfg.newSpenderA()),
                new Entry(cfg.ctf(),         Kind.CTF,   cfg.newSpenderB()),
                new Entry(cfg.ctf(),         Kind.CTF,   cfg.parlay())
        );
    }
}
