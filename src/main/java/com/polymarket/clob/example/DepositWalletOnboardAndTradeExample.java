package com.polymarket.clob.example;

import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.onboard.Onboarder;
import com.polymarket.clob.onboard.OnboardingConfig;
import com.polymarket.clob.onboard.OnboardingResult;
import com.polymarket.clob.onboard.TestOrderArgs;
import com.polymarket.clob.order.OrderType;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * Polymarket Deposit Wallet 端到端 onboard + trade 示例。
 *
 * <p>环境变量：
 * <ul>
 *   <li>{@code PK}（必填）—— EOA 私钥 hex（可带或不带 {@code 0x} 前缀）</li>
 *   <li>{@code RPC_URL}（可选）—— Polygon RPC，默认 {@code https://polygon-rpc.com}</li>
 *   <li>{@code TOKEN_ID}（可选）—— 设置后跑下单步骤，否则仅 onboarding</li>
 * </ul>
 *
 * <p>真链冒烟前置：deposit wallet 至少需要 1 USDC.e 才能下单（CLOB 会拒绝余额不足）。
 */
public final class DepositWalletOnboardAndTradeExample {

    private DepositWalletOnboardAndTradeExample() {}

    public static void main(String[] args) throws Exception {
        String pk = Objects.requireNonNull(System.getenv("PK"), "set PK env var (EOA private key)");
        String rpcUrl = Optional.ofNullable(System.getenv("RPC_URL")).orElse("https://polygon-rpc.com");
        String tokenIdStr = System.getenv("TOKEN_ID");

        Signer eoa = LocalSigner.fromPrivateKeyHex(pk);

        OnboardingConfig.Builder b = OnboardingConfig.builder().rpcUrl(URI.create(rpcUrl));
        if (tokenIdStr != null && !tokenIdStr.isBlank()) {
            b.testOrder(new TestOrderArgs(
                    new BigInteger(tokenIdStr),
                    Side.BUY,
                    new BigDecimal("0.1"),
                    new BigDecimal("5"),
                    OrderType.GTC,
                    "0.01",
                    false));
        }

        System.out.println("══════════════════════════════════════════════════════════════");
        System.out.println(" Polymarket Deposit Wallet Onboard + Trade");
        System.out.println("══════════════════════════════════════════════════════════════");
        System.out.println("EOA:     " + eoa.address().toHex());
        System.out.println("Chain:   Polygon (137)");
        System.out.println(tokenIdStr == null ? "Mode:    onboard only (TOKEN_ID not set)" : "Mode:    onboard + trade");

        Onboarder onboarder = new Onboarder(b.build());
        OnboardingResult r = onboarder.run(eoa).get();

        System.out.println("\n✅ Wallet:        " + r.wallet().toHex());
        System.out.println("   API key:       " + r.creds().apiKey());
        r.testOrder().ifPresent(resp -> System.out.println("   Order response: " + resp));
        System.out.println("\n══════════════════════════════════════════════════════════════");
    }
}
