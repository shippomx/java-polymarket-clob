package com.polymarket.clob.onboard;

import com.polymarket.clob.api.AuthApi;
import com.polymarket.clob.api.AuthApiImpl;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.chain.DepositWalletReads;
import com.polymarket.clob.chain.EvmRpcClient;
import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.chain.Web3jEvmRpcClient;
import com.polymarket.clob.deposit.ApprovalPlanner;
import com.polymarket.clob.deposit.BatchEip712;
import com.polymarket.clob.deposit.DepositWalletDerivation;
import com.polymarket.clob.deposit.DepositWalletRelayer;
import com.polymarket.clob.deposit.SignedBatch;
import com.polymarket.clob.gamma.GammaClient;
import com.polymarket.clob.gamma.GammaSession;
import com.polymarket.clob.http.HttpTransport;
import com.polymarket.clob.http.JsonCodec;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.ContractRegistry;
import com.polymarket.clob.order.PostOrderResponse;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 7 步 Deposit Wallet onboarding 编排器。每步先读再写，重入安全（幂等）。
 *
 * <ol>
 *   <li>Gamma SIWE 登录 → {@link GammaSession}</li>
 *   <li>派生 Deposit Wallet 地址</li>
 *   <li>确保 Gamma 用户档案存在（幂等）</li>
 *   <li>若钱包未部署 → 通过 relayer 执行 WALLET-CREATE + waitForTx</li>
 *   <li>若有缺失 allowance → 通过 relayer 提交 batch + waitForTx</li>
 *   <li>派生或创建 CLOB API 凭证（{@link AuthApi#createOrDeriveApiKey}）</li>
 *   <li>（可选）若 {@code cfg.testOrder()} 存在，提交 POLY_1271 测试订单</li>
 * </ol>
 */
public final class Onboarder {

    private final OnboardingConfig cfg;
    private final HttpClient http;
    private final EvmRpcClient rpc;
    private final GammaClient gamma;
    private final DepositWalletReads reads;
    private final DepositWalletDerivation derivation;

    /**
     * 生产入口：使用真实的 {@link Web3jEvmRpcClient}。
     */
    public Onboarder(OnboardingConfig cfg) {
        this(cfg, new Web3jEvmRpcClient(cfg.rpcUrl()));
    }

    /**
     * 测试入口：注入 stub {@link EvmRpcClient}，避免真实 RPC 调用。
     */
    public static Onboarder forTesting(OnboardingConfig cfg, EvmRpcClient rpc) {
        return new Onboarder(cfg, rpc);
    }

    private Onboarder(OnboardingConfig cfg, EvmRpcClient rpc) {
        this.cfg = cfg;
        this.http = cfg.httpClient() != null ? cfg.httpClient() : HttpClient.newHttpClient();
        this.rpc = rpc;
        this.gamma = new GammaClient(cfg.gammaHost(), http);
        this.reads = new DepositWalletReads(rpc, cfg.chainId());
        this.derivation = new DepositWalletDerivation(reads);
    }

    /**
     * 执行完整的 7 步 onboarding 流程，返回 {@link OnboardingResult}。
     *
     * @param eoa EOA 签名器（本地私钥或远程签名器）
     * @return 完成后的 onboarding 结果，包含 wallet 地址、API 凭证与可选的测试订单响应
     */
    public CompletableFuture<OnboardingResult> run(Signer eoa) {
        // 步骤 1: Gamma SIWE 登录
        return gamma.loginWithSiwe(eoa, cfg.chainId())
                .thenCompose(session ->
                    // 步骤 2: 派生 Deposit Wallet 地址
                    derivation.predictWalletAddress(eoa.address())
                        .thenCompose(wallet ->
                            // 步骤 3: 确保 Gamma 用户档案存在
                            gamma.ensureProfile(session, eoa.address(), wallet)
                                .thenCompose(v ->
                                    // 步骤 4: 若未部署则部署钱包
                                    deployIfNeeded(eoa, wallet, session)
                                        .thenCompose(v2 ->
                                            // 步骤 5: 若有缺失 allowance 则提交 batch
                                            applyApprovals(eoa, wallet, session)
                                                .thenCompose(v3 ->
                                                    // 步骤 6: 派生或创建 CLOB API 凭证
                                                    deriveOrCreateCreds(eoa)
                                                        .thenCompose(creds ->
                                                            // 步骤 7（可选）: 提交测试订单
                                                            placeOptionalOrder(eoa, wallet, creds)
                                                                .thenApply(orderResp ->
                                                                    new OnboardingResult(wallet, creds, session, orderResp))
                                                        )
                                                )
                                        )
                                )
                        )
                );
    }

    // ---- 步骤 4: 部署钱包（幂等）----

    private CompletableFuture<Void> deployIfNeeded(Signer eoa, Address wallet, GammaSession session) {
        return derivation.isDeployed(wallet).thenCompose(deployed -> {
            if (deployed) {
                return CompletableFuture.completedFuture(null);
            }
            try {
                DepositWalletRelayer relayer = buildRelayer(session);
                String txId = relayer.submitWalletCreate(eoa.address(), PolymarketContracts.FACTORY);
                relayer.waitForTx(txId, cfg.relayerPollInterval(), cfg.relayerMaxAttempts());
                return CompletableFuture.<Void>completedFuture(null);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return CompletableFuture.failedFuture(e);
            }
        });
    }

    // ---- 步骤 5: 批量授权（幂等）----

    private CompletableFuture<Void> applyApprovals(Signer eoa, Address wallet, GammaSession session) {
        var dwCfg = ContractRegistry.depositWalletConfig(cfg.chainId()).orElseThrow(
                () -> new IllegalStateException("DepositWallet not deployed on chainId " + cfg.chainId()));
        var planner = new ApprovalPlanner(reads, dwCfg);

        return planner.planMissingApprovals(wallet).thenCompose(calls -> {
            if (calls.isEmpty()) {
                // 所有 allowance 已满，跳过 batch 提交
                return CompletableFuture.completedFuture(null);
            }
            return reads.walletNonce(wallet).thenCompose(nonce -> {
                BigInteger deadline = BigInteger.valueOf(Instant.now().getEpochSecond() + 1800);
                byte[] digest = BatchEip712.hashBatch(cfg.chainId(), wallet, nonce, deadline, calls);
                return eoa.signHash(digest).thenCompose(sig65 -> {
                    SignedBatch batch = new SignedBatch(
                            eoa.address(),
                            PolymarketContracts.FACTORY,
                            wallet,
                            nonce,
                            deadline,
                            calls,
                            sig65);
                    try {
                        DepositWalletRelayer relayer = buildRelayer(session);
                        String txId = relayer.submitBatch(batch);
                        relayer.waitForTx(txId, cfg.relayerPollInterval(), cfg.relayerMaxAttempts());
                        return CompletableFuture.<Void>completedFuture(null);
                    } catch (IOException | InterruptedException e) {
                        if (e instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        return CompletableFuture.<Void>failedFuture(e);
                    }
                });
            });
        });
    }

    // ---- 步骤 6: 派生或创建 CLOB API 凭证 ----

    private CompletableFuture<ApiCredentials> deriveOrCreateCreds(Signer eoa) {
        // AuthApiImpl 需要 HttpTransport（不是 HttpClient），用 cfg.clobHost() 作为 baseUri
        HttpTransport transport = HttpTransport.builder()
                .baseUri(cfg.clobHost())
                .httpClient(http)
                .objectMapper(JsonCodec.objectMapper())
                .requestTimeout(Duration.ofSeconds(30))
                .build();
        AuthApi auth = new AuthApiImpl(transport, cfg.chainId());
        long ts = Instant.now().getEpochSecond();
        // createOrDeriveApiKey: 先 POST /auth/api-key，4xx 时回落到 GET /auth/derive-api-key
        return auth.createOrDeriveApiKey(eoa, cfg.chainId(), ts, BigInteger.ZERO);
    }

    // ---- 步骤 7（可选）: 提交测试订单 ----

    private CompletableFuture<Optional<PostOrderResponse>> placeOptionalOrder(
            Signer eoa, Address wallet, ApiCredentials creds) {
        if (cfg.testOrder().isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        // TODO(Task 21): 实现 POLY_1271 测试订单提交
        // 需要 OrderBuilder + OrderApi + LimitOrderArgsV2 + CreateOrderOptions
        // 此处暂时返回 empty，端到端冒烟测试（Task 21）覆盖此路径
        return CompletableFuture.completedFuture(Optional.empty());
    }

    // ---- 内部工具 ----

    private DepositWalletRelayer buildRelayer(GammaSession session) {
        return new DepositWalletRelayer(cfg.relayerHost(), http, session);
    }
}
