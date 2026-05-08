package com.polymarket.clob.example;

import com.fasterxml.jackson.core.type.TypeReference;
import com.polymarket.clob.AuthenticatedClobClient;
import com.polymarket.clob.ClobClient;
import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.auth.UnsignedClobAuth;
import com.polymarket.clob.chain.DepositWalletReads;
import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.chain.Web3jEvmRpcClient;
import com.polymarket.clob.deposit.ApprovalPlanner;
import com.polymarket.clob.deposit.Call;
import com.polymarket.clob.deposit.DepositWalletDerivation;
import com.polymarket.clob.deposit.DepositWalletRelayer;
import com.polymarket.clob.deposit.RelayerTxResult;
import com.polymarket.clob.deposit.SignedBatch;
import com.polymarket.clob.deposit.UnsignedBatch;
import com.polymarket.clob.gamma.GammaClient;
import com.polymarket.clob.gamma.GammaSession;
import com.polymarket.clob.http.HttpTransport;
import com.polymarket.clob.http.JsonCodec;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.ContractRegistry;
import com.polymarket.clob.order.OrderRounding;
import com.polymarket.clob.order.OrderType;
import com.polymarket.clob.order.OrderV2;
import com.polymarket.clob.order.PostOrderResponse;
import com.polymarket.clob.order.SaltSource;
import com.polymarket.clob.order.SignedOrderV2;
import com.polymarket.clob.order.TickSize;
import com.polymarket.clob.order.UnsignedOrderV2Pol1271;
import com.polymarket.clob.signing.ExternalSigning;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Polymarket Deposit Wallet 端到端原语级演示 —— <b>外部签名</b>变体。
 *
 * <p>与 {@link FullOnboardAndTradeExample} 流程一一对应，但每处需要 EOA 签名的地方
 * 都拆成两阶段：BE 进程构造 unsigned typed-data → "App 端"对 32 字节 digest 签 → BE 把
 * 65 字节签名灌回 attach。本文件用 {@link LocalSigner} 模拟 App 端的本地钱包，标注
 * {@code [APP]}；其余调用都是 BE-only 的工序，标注 {@code [BE]}。
 *
 * <p>本演示覆盖 4 类外部签名 payload 中的 3 类：
 * <ol>
 *   <li>{@link UnsignedBatch} —— Step 5 approve-batch 链上代付；</li>
 *   <li>{@link UnsignedClobAuth} —— Step 6 派生 L2 API 凭证；</li>
 *   <li>{@link UnsignedOrderV2Pol1271} —— Step 7 ERC-7739 嵌套 TypedDataSign 下单。</li>
 * </ol>
 * 第 4 类 SIWE 仍走 {@link GammaClient#loginWithSiwe}，因为它持有 Bearer 鉴权头与 cookie 协商，
 * 不属于纯签名原语；只想要 SIWE digest 的消费者可调 {@link com.polymarket.clob.gamma.UnsignedSiwe#buildUnsigned}。
 *
 * <p>环境变量与 {@link FullOnboardAndTradeExample} 一致；为了和现有冒烟脚本互操作，
 * pk / token id / API 凭证在源码内同样以硬编码形式给出，便于直接 {@code mvn exec:java} 运行。
 */
public final class FullExternalSigningExample {

    // ---- 网络常量 ----
    private static final long CHAIN_ID = 137L;
    private static final URI RELAYER_HOST = URI.create("https://relayer-v2.polymarket.com");
    private static final URI GAMMA_HOST = URI.create("https://gamma-api.polymarket.com");
    private static final String DEFAULT_RPC_URL = "https://polygon-rpc.com";
    private static final String DEFAULT_CLOB_URL = "https://clob.polymarket.com";

    // ---- Relayer 轮询参数 ----
    private static final Duration RELAYER_POLL_INTERVAL = Duration.ofSeconds(3);
    private static final int RELAYER_MAX_ATTEMPTS = 200;

    // ---- 测试单参数 ----
    private static final BigDecimal TEST_PRICE = new BigDecimal("0.1");
    private static final BigDecimal TEST_SIZE = new BigDecimal("5");
    private static final Side TEST_SIDE = Side.BUY;
    private static final OrderType TEST_ORDER_TYPE = OrderType.GTC;
    private static final String TEST_TICK_SIZE = "0.01";
    private static final boolean TEST_NEG_RISK = false;
    private static final String ZERO32 = "0x" + "0".repeat(64);

    private FullExternalSigningExample() {}

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
        // ---- 解析输入 ----
        String pk = "0x582218b470497c1640cf05a529ca073e86931bfec9a3500703aaf0b3bcb3baa7";
        String tokenIdStr = "8501497159083948713316135768103773293754490207922884688769443031624417212426";

        String rpcUrl = "https://polygon.drpc.org";
        String clobUrl = Optional.ofNullable(System.getenv("CLOB_API_URL")).orElse(DEFAULT_CLOB_URL);
        String envApiKey = "f04280f5-8f77-d4c6-4a26-b44e0b3a78ff";
        String envSecret = "_QO5cL_YVvr-fJn3SlHwLozQHDySYgFnVC8H5JJCJ60=";
        String envPassphrase = "2bf76ac5007da37b78b772e0e239acec5e6bab3115a8f7daa03a99272fea4e2f";

        // [APP] 模拟手机端钱包，唯一持有私钥的对象；BE 进程仅通过 signHash(digest32) 与之交互。
        Signer appSigner = LocalSigner.fromPrivateKeyHex(pk);
        Address eoa = appSigner.address();

        HttpClient http = HttpClient.newHttpClient();

        printSection(" Polymarket External-Signing E2E Test (Java, BE-only primitives)");
        System.out.println("EOA:     " + eoa.toHex() + "  [APP holds private key, BE never touches it]");
        System.out.println("Chain:   Polygon (" + CHAIN_ID + ")");

        // ---- Step 1: Gamma SIWE login ----
        // BE doc §3.2.A: App 直连 gamma /nonce + /login，BE 仅透传 cookie。这里把 GammaClient 当 App 用。
        step(1, "Gamma SIWE login (App-side; produces cookie)");
        GammaClient gamma = new GammaClient(GAMMA_HOST, http);
        GammaSession session = gamma.loginWithSiwe(appSigner, CHAIN_ID).get();
        System.out.println("    [APP] cookie obtained, transmits to BE for relayer outbound");

        // ---- Step 2: Predict deposit wallet (no signing) ----
        step(2, "[BE] Predict deposit wallet address (no signing)");
        Web3jEvmRpcClient rpc = new Web3jEvmRpcClient(URI.create(rpcUrl));
        DepositWalletReads reads = new DepositWalletReads(rpc, CHAIN_ID);
        DepositWalletDerivation derivation = new DepositWalletDerivation(reads);
        Address wallet = derivation.predictWalletAddress(eoa).get();
        boolean deployed = derivation.isDeployed(wallet).get();
        System.out.println("    wallet:   " + wallet.toHex());
        System.out.println("    deployed: " + deployed);

        // ---- Step 3: Ensure Gamma profile ----
        step(3, "[BE] Ensure Gamma profile (idempotent, cookie auth, no signing)");
        gamma.ensureProfile(session, eoa, wallet).get();
        System.out.println("    profile ensured");

        // ---- Step 4: Deploy wallet if needed (no signing per BE doc) ----
        DepositWalletRelayer relayer = new DepositWalletRelayer(RELAYER_HOST, http, session);
        if (deployed) {
            step(4, "Wallet already deployed — skip");
        } else {
            step(4, "[BE] Deploy wallet via WALLET-CREATE (cookie auth, no signing)");
            String txId = relayer.submitWalletCreate(eoa, PolymarketContracts.FACTORY);
            System.out.println("    txnID=" + txId);
            RelayerTxResult result = relayer.waitForTx(txId, RELAYER_POLL_INTERVAL, RELAYER_MAX_ATTEMPTS);
            System.out.println("    state=" + result.state() + " hash=" + result.txHash());
        }

        // ---- Step 5: Approval batch via UnsignedBatch + attachBatchSignature ----
        step(5, "Check allowances and approve missing (external signing path)");
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

            // [BE] 构造 unsigned Batch；BE 不持有私钥，digest 是给 App 看的唯一权威输入。
            UnsignedBatch unsigned = ExternalSigning.buildUnsignedBatch(
                    CHAIN_ID, eoa, wallet, nonce, deadline, calls);
            System.out.println("    [BE] unsigned digest = " + hex(unsigned.signingDigest32()));

            // [APP] 模拟手机端用私钥对 32 字节 digest 出 65B 签名。生产中此处是 HTTP 往返。
            byte[] sig65 = appSigner.signHash(unsigned.signingDigest32()).get();
            System.out.println("    [APP] returned 65B sig (truncated)= " + hex(sig65).substring(0, 18) + "...");

            // [BE] 灌回签名得到完整 SignedBatch，可直接发给 relayer-v2。
            SignedBatch batch = ExternalSigning.attachBatchSignature(unsigned, sig65);
            String txId = relayer.submitBatch(batch);
            System.out.println("    [BE] txnID=" + txId);
            RelayerTxResult result = relayer.waitForTx(txId, RELAYER_POLL_INTERVAL, RELAYER_MAX_ATTEMPTS);
            System.out.println("    [BE] state=" + result.state() + " hash=" + result.txHash());
        }

        // ---- Step 6: Derive L2 API credentials via UnsignedClobAuth + attachClobAuthSignature ----
        step(6, "Get CLOB L2 API credentials (external signing path for L1 derive)");
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
            long ts = Instant.now().getEpochSecond();

            // [BE] 构造 unsigned ClobAuth typed-data。
            UnsignedClobAuth unsigned = ExternalSigning.buildUnsignedClobAuth(eoa, CHAIN_ID, ts, BigInteger.ZERO);
            System.out.println("    [BE] unsigned digest = " + hex(unsigned.signingDigest32()));

            // [APP] 用 EOA 私钥签 32 字节 digest（生产中是 App 弹窗 + 用户确认）。
            byte[] sig65 = appSigner.signHash(unsigned.signingDigest32()).get();
            System.out.println("    [APP] returned 65B sig (truncated)= " + hex(sig65).substring(0, 18) + "...");

            // [BE] 灌回签名得到 4 个 POLY_* 头；用这套头直接调 /auth/derive-api-key（已存在 EOA → derive）
            // 或 /auth/api-key（新建）。本演示走 derive。
            Map<String, String> headers = ExternalSigning.attachClobAuthSignature(unsigned, sig65);
            creds = transport.get("auth/derive-api-key", Map.of(), headers,
                    new TypeReference<ApiCredentials>() {}).get();

            System.out.println("    Derived new creds. Save to .env to skip this step next time:");
            System.out.println("      CLOB_API_KEY=" + creds.apiKey());
            System.out.println("      CLOB_SECRET=" + creds.secret());
            System.out.println("      CLOB_PASS_PHRASE=" + creds.passphrase());
        }

        // ---- Step 7: Place order via UnsignedOrderV2Pol1271 + attachOrderV2Pol1271Signature ----
        if (tokenIdStr == null || tokenIdStr.isBlank()) {
            step(7, "(skipped — TOKEN_ID not set)");
            printSection(" ✅ External signing onboarding complete (no TOKEN_ID, order step skipped)");
            return;
        }

        step(7, "Place test order via ERC-7739 nested TypedDataSign (external signing path)");
        BigInteger usdcBalance = reads.erc20BalanceOf(PolymarketContracts.USDC_E, wallet).get();
        System.out.println("    Deposit wallet USDC.e balance: " + usdcBalance);
        if (usdcBalance.compareTo(BigInteger.valueOf(1_000_000L)) < 0) {
            System.out.println("    ⚠️  Wallet has < 1 USDC — order will likely be rejected by CLOB.");
            System.out.println("       Send some USDC.e to " + wallet.toHex() + " and re-run.");
        }

        // [BE] 构造 OrderV2 不可变模型（份额/金额按 tickSize 舍入）。
        TickSize tickSize = parseTickSize(TEST_TICK_SIZE);
        OrderRounding.Amounts amts = OrderRounding.limit(TEST_SIDE, TEST_SIZE, TEST_PRICE, tickSize.rounding());
        OrderV2 order = OrderV2.builder()
                .salt(SaltSource.secureRandom().next())
                .maker(wallet)
                .signer(wallet)
                .tokenId(parseTokenId(tokenIdStr))
                .makerAmount(amts.maker())
                .takerAmount(amts.taker())
                .side(TEST_SIDE)
                .signatureType(SignatureType.POLY_1271)
                .timestamp(BigInteger.valueOf(System.currentTimeMillis()))
                .expiration(BigInteger.ZERO)
                .metadata(ZERO32)
                .builder(ZERO32)
                .build();

        // [BE] buildUnsigned 同时给出 32B innerDigest（权威签名输入）和 ERC-7739 嵌套
        // TypedDataSign envelope JSON（App 钱包可走 eth_signTypedData_v4 弹窗）。
        UnsignedOrderV2Pol1271 unsignedOrder =
                ExternalSigning.buildUnsignedOrderV2Pol1271(order, CHAIN_ID, TEST_NEG_RISK);
        System.out.println("    [BE] inner digest = " + hex(unsignedOrder.signingDigest32()));
        System.out.println("    [BE] typedData JSON length = " + unsignedOrder.typedDataJson().length() + " chars");

        // [APP] 模拟 App 端对 inner digest 签 → 65B inner sig。
        byte[] innerSig = appSigner.signHash(unsignedOrder.signingDigest32()).get();
        System.out.println("    [APP] returned 65B inner sig (truncated)= " + hex(innerSig).substring(0, 18) + "...");

        // [BE] attach 拼出 wire-level signature：innerSig | appDomainSep | contentsHash | ORDER_TYPE | lenBE(2)。
        SignedOrderV2 signedOrder = ExternalSigning.attachOrderV2Pol1271Signature(unsignedOrder, innerSig);
        System.out.println("    [BE] wire signature length = "
                + (signedOrder.getSignature().length() - 2) / 2 + " bytes");

        // [BE] 用已存的 L2 凭证 + appSigner.address() 作为 caller，提交已签名订单。
        // 注：postOrderV2 内部用 L2 HMAC 头，与外部签名无关；它只看 caller 地址做 POLY_ADDRESS 头。
        try (ClobClient base = ClobClient.builder()
                .endpoint(URI.create(clobUrl))
                .chainId(CHAIN_ID)
                .httpClient(http)
                .build();
             AuthenticatedClobClient client = base.authenticate(
                     appSigner, SignatureType.POLY_1271, wallet, creds)) {
            PostOrderResponse resp = client.postOrderV2(signedOrder, TEST_ORDER_TYPE, false).get();
            System.out.println("    [BE] CLOB response: " + resp);
        }

        printSection(" ✅ External-signing pipeline succeeded — BE never held the private key");
    }

    // ---- helpers ----

    private static void step(int n, String label) {
        System.out.println();
        System.out.println("[" + n + "/7] " + label);
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

    private static BigInteger parseTokenId(String s) {
        String t = s.trim();
        if (t.startsWith("0x") || t.startsWith("0X")) {
            return new BigInteger(t.substring(2), 16);
        }
        return new BigInteger(t);
    }

    private static String hex(byte[] bytes) {
        return "0x" + java.util.HexFormat.of().formatHex(bytes);
    }
}
