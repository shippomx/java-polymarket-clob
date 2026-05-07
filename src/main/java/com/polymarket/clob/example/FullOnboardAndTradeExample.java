package com.polymarket.clob.example;

import com.polymarket.clob.AuthenticatedClobClient;
import com.polymarket.clob.ClobClient;
import com.polymarket.clob.api.AuthApi;
import com.polymarket.clob.api.AuthApiImpl;
import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.chain.DepositWalletReads;
import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.chain.Web3jEvmRpcClient;
import com.polymarket.clob.deposit.ApprovalPlanner;
import com.polymarket.clob.deposit.BatchEip712;
import com.polymarket.clob.deposit.Call;
import com.polymarket.clob.deposit.DepositWalletDerivation;
import com.polymarket.clob.deposit.DepositWalletRelayer;
import com.polymarket.clob.deposit.RelayerTxResult;
import com.polymarket.clob.deposit.SignedBatch;
import com.polymarket.clob.funxyz.DepositAddresses;
import com.polymarket.clob.funxyz.FunxyzClient;
import com.polymarket.clob.funxyz.FunxyzConfig;
import com.polymarket.clob.gamma.GammaClient;
import com.polymarket.clob.gamma.GammaSession;
import com.polymarket.clob.http.HttpTransport;
import com.polymarket.clob.http.JsonCodec;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.ContractRegistry;
import com.polymarket.clob.order.CreateOrderOptions;
import com.polymarket.clob.order.LimitOrderArgsV2;
import com.polymarket.clob.order.OrderType;
import com.polymarket.clob.order.PostOrderResponse;
import com.polymarket.clob.order.TickSize;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Polymarket Deposit Wallet 端到端原语级演示（不经过 {@link com.polymarket.clob.onboard.Onboarder}）。
 *
 * <p>对照 TS 版本：{@code clob-client-v2/examples/account/fullOnboardAndTrade.ts}。
 *
 * <p>用途：当生产或冒烟流程在某一步失败时，按本文件逐步定位（钱包派生 / 部署 /
 * 授权 / API key / 下单），打印链上 {@code txnID} / {@code state} / {@code hash} /
 * 响应 JSON。如果只想"一键跑通"，用 {@link OnboarderExample}。
 *
 * <p>环境变量（与 TS 完全一致）：
 * <ul>
 *   <li>{@code PK}（必填）—— EOA 私钥 hex</li>
 *   <li>{@code RPC_URL}（可选）—— 默认 {@code https://polygon-rpc.com}</li>
 *   <li>{@code TOKEN_ID}（可选）—— 缺省 → 跳过步骤 7</li>
 *   <li>{@code CLOB_API_URL}（可选）—— 默认 {@code https://clob.polymarket.com}</li>
 *   <li>{@code CLOB_API_KEY} / {@code CLOB_SECRET} / {@code CLOB_PASS_PHRASE}（可选三件套）——
 *       全给 → 跳过派生；缺任一 → 派生新凭证并打印保存提示</li>
 * </ul>
 */
public final class FullOnboardAndTradeExample {

    // ---- 网络常量（与 TS const 块对齐）----
    private static final long CHAIN_ID = 137L;
    private static final URI RELAYER_HOST = URI.create("https://relayer-v2.polymarket.com");
    private static final URI GAMMA_HOST = URI.create("https://gamma-api.polymarket.com");
    private static final String DEFAULT_RPC_URL = "https://polygon-rpc.com";
    private static final String DEFAULT_CLOB_URL = "https://clob.polymarket.com";

    // ---- Relayer 轮询参数（与 OnboardingConfig 默认值一致）----
    private static final Duration RELAYER_POLL_INTERVAL = Duration.ofSeconds(3);
    private static final int RELAYER_MAX_ATTEMPTS = 200;

    // ---- 测试单参数 ----
    private static final BigDecimal TEST_PRICE = new BigDecimal("0.1");
    private static final BigDecimal TEST_SIZE = new BigDecimal("5");
    private static final Side TEST_SIDE = Side.BUY;
    private static final OrderType TEST_ORDER_TYPE = OrderType.GTC;
    private static final String TEST_TICK_SIZE = "0.01";
    private static final boolean TEST_NEG_RISK = false;

    private FullOnboardAndTradeExample() {}

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            System.err.println("\n❌ Test failed: " + t.getMessage());
            Throwable cause = t;
            while (cause != null) {
                if (cause instanceof com.polymarket.clob.exception.ClobApiException e) {
                    System.err.println("    upstream body: " + e.getBody());
                    break;
                }
                cause = cause.getCause();
            }
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        // ---- 解析环境变量 ----
        String pk = "0x582218b470497c1640cf05a529ca073e86931bfec9a3500703aaf0b3bcb3baa7";
        String tokenIdStr = "8501497159083948713316135768103773293754490207922884688769443031624417212426";

        String rpcUrl = "https://polygon.drpc.org";
        String clobUrl = Optional.ofNullable(System.getenv("CLOB_API_URL")).orElse(DEFAULT_CLOB_URL);
        String envApiKey = System.getenv("CLOB_API_KEY");
        String envSecret = System.getenv("CLOB_SECRET");
        String envPassphrase = System.getenv("CLOB_PASS_PHRASE");

        Signer eoa = LocalSigner.fromPrivateKeyHex(pk);
        HttpClient http = HttpClient.newHttpClient();

        printSection(" Polymarket Deposit Wallet Onboard + Trade Test (Java, primitive-level)");
        System.out.println("EOA:     " + eoa.address().toHex());
        System.out.println("Chain:   Polygon (" + CHAIN_ID + ")");

        // ---- Step 1: Gamma SIWE login ----
        step(1, "Gamma SIWE login → relayer cookie");
        GammaClient gamma = new GammaClient(GAMMA_HOST, http);
        GammaSession session = gamma.loginWithSiwe(eoa, CHAIN_ID).get();
        System.out.println("    ✅ logged in");

        // ---- Step 2: Derive deposit wallet ----
        step(2, "Derive deposit wallet address from EOA");
        Web3jEvmRpcClient rpc = new Web3jEvmRpcClient(URI.create(rpcUrl));
        DepositWalletReads reads = new DepositWalletReads(rpc, CHAIN_ID);
        DepositWalletDerivation derivation = new DepositWalletDerivation(reads);
        Address wallet = derivation.predictWalletAddress(eoa.address()).get();
        boolean deployed = derivation.isDeployed(wallet).get();
        System.out.println("    id:       " + leftPadEoaTo32(eoa.address()));
        System.out.println("    wallet:   " + wallet.toHex());
        System.out.println("    deployed: " + deployed);

        // ---- Step 3: Fetch fun.xyz on-ramp deposit addresses ----
        step(3, "Fetch fun.xyz on-ramp deposit addresses");
        FunxyzClient funxyz = new FunxyzClient(FunxyzConfig.builder().build());
        DepositAddresses funding = funxyz.getDepositAddresses(eoa.address(), wallet).get();
        System.out.println("    EVM (Polygon):  " + funding.evm().toHex());
        System.out.println("    Solana:         " + funding.solana());
        System.out.println("    Tron:           " + funding.tron());
        System.out.println("    BTC (segwit):   " + funding.btcSegwit());

        // ---- Step 4: Ensure Gamma profile ----
        step(4, "Ensure Gamma profile (idempotent)");
        gamma.ensureProfile(session, eoa.address(), wallet).get();
        System.out.println("    ✅ profile ensured");

        // ---- Step 5: Deploy if needed ----
        DepositWalletRelayer relayer = new DepositWalletRelayer(RELAYER_HOST, http, session);
        if (deployed) {
            step(5, "Wallet already deployed — skip");
        } else {
            step(5, "Deploy wallet via WALLET-CREATE");
            String txId = relayer.submitWalletCreate(eoa.address(), PolymarketContracts.FACTORY);
            System.out.println("    txnID=" + txId);
            RelayerTxResult result = relayer.waitForTx(txId, RELAYER_POLL_INTERVAL, RELAYER_MAX_ATTEMPTS);
            System.out.println("    state=" + result.state() + " hash=" + result.txHash());
        }

        // ---- Step 6: Approvals batch ----
        step(6, "Check allowances and approve missing");
        var dwCfg = ContractRegistry.depositWalletConfig(CHAIN_ID).orElseThrow(
                () -> new IllegalStateException("DepositWallet not deployed on chainId " + CHAIN_ID));
        ApprovalPlanner planner = new ApprovalPlanner(reads, dwCfg);
        List<Call> calls = planner.planMissingApprovals(wallet).get();
        if (calls.isEmpty()) {
            System.out.println("    All allowances already set ✅");
        } else {
            System.out.println("    Submitting " + calls.size() + " approval(s) via WALLET batch...");
            BigInteger nonce = reads.walletNonce(wallet).get();
            BigInteger deadline = BigInteger.valueOf(Instant.now().getEpochSecond() + 1800);
            byte[] digest = BatchEip712.hashBatch(CHAIN_ID, wallet, nonce, deadline, calls);
            byte[] sig65 = eoa.signHash(digest).get();
            SignedBatch batch = new SignedBatch(
                    eoa.address(),
                    PolymarketContracts.FACTORY,
                    wallet,
                    nonce,
                    deadline,
                    calls,
                    sig65);
            String txId = relayer.submitBatch(batch);
            System.out.println("    txnID=" + txId);
            RelayerTxResult result = relayer.waitForTx(txId, RELAYER_POLL_INTERVAL, RELAYER_MAX_ATTEMPTS);
            System.out.println("    state=" + result.state() + " hash=" + result.txHash());
        }

        // ---- Step 7: CLOB API credentials ----
        step(7, "Get CLOB L2 API credentials");
        ApiCredentials creds;
        if (envApiKey != null && !envApiKey.isBlank()
                && envSecret != null && !envSecret.isBlank()
                && envPassphrase != null && !envPassphrase.isBlank()) {
            creds = new ApiCredentials(envApiKey, envSecret, envPassphrase);
            System.out.println("    Using existing creds from .env");
        } else {
            HttpTransport transport = HttpTransport.builder()
                    .baseUri(URI.create(clobUrl))
                    .httpClient(http)
                    .objectMapper(JsonCodec.objectMapper())
                    .requestTimeout(Duration.ofSeconds(30))
                    .build();
            AuthApi auth = new AuthApiImpl(transport, CHAIN_ID);
            long ts = Instant.now().getEpochSecond();
            creds = auth.createOrDeriveApiKey(eoa, CHAIN_ID, ts, BigInteger.ZERO).get();
            System.out.println("    Derived new creds. Save to .env to skip this step next time:");
            System.out.println("      CLOB_API_KEY=" + creds.apiKey());
            System.out.println("      CLOB_SECRET=" + creds.secret());
            System.out.println("      CLOB_PASS_PHRASE=" + creds.passphrase());
        }

        // ---- Step 8: Test order (optional) ----
        if (tokenIdStr == null || tokenIdStr.isBlank()) {
            step(8, "(skipped — TOKEN_ID not set)");
            printSection(" ✅ Onboarding complete (no TOKEN_ID, order step skipped)");
            return;
        }

        step(8, "Place test order (token=" + tokenIdStr + ")");
        BigInteger usdcBalance = reads.erc20BalanceOf(PolymarketContracts.USDC_E, wallet).get();
        System.out.println("    Deposit wallet USDC.e balance: " + usdcBalance);
        // if (usdcBalance.compareTo(BigInteger.valueOf(1_000_000L)) < 0) {
        //     System.out.println("    ⚠️  Wallet has < 1 USDC — order will likely be rejected by CLOB.");
        //     System.out.println("       Send some USDC.e to " + wallet.toHex() + " and re-run.");
        // }

        try (ClobClient base = ClobClient.builder()
                .endpoint(URI.create(clobUrl))
                .chainId(CHAIN_ID)
                .httpClient(http)
                .build();
             AuthenticatedClobClient client = base.authenticate(
                     eoa, SignatureType.POLY_1271, wallet, creds)) {
            LimitOrderArgsV2 order = LimitOrderArgsV2.builder()
                    .tokenId(parseTokenId(tokenIdStr))
                    .side(TEST_SIDE)
                    .price(TEST_PRICE)
                    .size(TEST_SIZE)
                    .build();
            CreateOrderOptions options = CreateOrderOptions.of(parseTickSize(TEST_TICK_SIZE), TEST_NEG_RISK);
            PostOrderResponse resp = client.createAndPostLimitOrderV2(
                    order, options, TEST_ORDER_TYPE, false).get();
            System.out.println("    Response: " + resp);
        }

        printSection(" ✅ Full onboard + trade pipeline succeeded");
    }

    // ---- helpers ----

    private static void step(int n, String label) {
        System.out.println();
        System.out.println("[" + n + "/8] " + label);
    }

    private static void printSection(String title) {
        String bar = "══════════════════════════════════════════════════════════════";
        System.out.println();
        System.out.println(bar);
        System.out.println(title);
        System.out.println(bar);
    }

    private static TickSize parseTickSize(String s) {
        return switch (s) {
            case "0.1"    -> TickSize.TS_0_1;
            case "0.01"   -> TickSize.TS_0_01;
            case "0.001"  -> TickSize.TS_0_001;
            case "0.0001" -> TickSize.TS_0_0001;
            default -> throw new IllegalArgumentException("Unsupported tickSize: " + s);
        };
    }

    /** TS 端 {@code pad(eoa, {size: 32})} 的等价实现：左补 12 字节零，得到 32B 工厂派生 id。 */
    private static String leftPadEoaTo32(Address eoa) {
        return "0x" + "0".repeat(24) + eoa.toLowerHex().substring(2);
    }

    /** 兼容十进制（标准 Polymarket token id）与 0x 前缀十六进制两种写法。 */
    private static BigInteger parseTokenId(String s) {
        String t = s.trim();
        if (t.startsWith("0x") || t.startsWith("0X")) {
            return new BigInteger(t.substring(2), 16);
        }
        return new BigInteger(t);
    }
}
