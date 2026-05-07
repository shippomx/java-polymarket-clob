# FullOnboardAndTradeExample Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `example/` 包中新增一份原语级（不经过 `Onboarder`）端到端 onboard + trade 演示文件 `FullOnboardAndTradeExample.java`，并将现有 `DepositWalletOnboardAndTradeExample.java` 重命名为 `OnboarderExample.java`，让两份 example 在用途定位上有清晰区分。

**Architecture:** 单文件、`main(String[])` 入口；7 步线性 stdout 进度（与 TS `clob-client-v2/examples/account/fullOnboardAndTrade.ts` 抽象层级 1:1 对齐）；直接组合 `GammaClient` / `Web3jEvmRpcClient` / `DepositWalletReads` / `DepositWalletDerivation` / `ApprovalPlanner` / `BatchEip712` / `DepositWalletRelayer` / `AuthApi` / `AuthenticatedClobClient`；不引入新主代码 API、不新增自动化测试。

**Tech Stack:** Java 21 (records / switch expressions / `var`)、Maven、`HttpClient`、Web3j 4.12.2、本仓库现有 SDK 类型。

**Spec:** [`docs/superpowers/specs/2026-05-07-full-onboard-and-trade-example-design.md`](../specs/2026-05-07-full-onboard-and-trade-example-design.md)

---

## File Map

| 操作 | 路径 | 责任 |
|---|---|---|
| Rename | `src/main/java/com/polymarket/clob/example/DepositWalletOnboardAndTradeExample.java` → `…/example/OnboarderExample.java` | 高层一键流水线 example（重命名后只改类名，不改逻辑） |
| Create | `src/main/java/com/polymarket/clob/example/FullOnboardAndTradeExample.java` | 原语级 7 步排障样板（≈ 230 行单文件） |
| Modify | `README.md`（行 94、447、463、479） | 4 处文本同步：链接、`mvn exec:java` 命令、迁移表、example 总表 |
| Modify | `CHANGELOG.md`（顶部新增段） | 标注重命名（破坏性）+ 新增 |

---

### Task 1: 重命名 `DepositWalletOnboardAndTradeExample` → `OnboarderExample`

**Files:**
- Move: `src/main/java/com/polymarket/clob/example/DepositWalletOnboardAndTradeExample.java` → `src/main/java/com/polymarket/clob/example/OnboarderExample.java`
- Modify: `src/main/java/com/polymarket/clob/example/OnboarderExample.java`（改类名 + 改 javadoc 引用）

- [ ] **Step 1: `git mv` 文件**

```bash
git mv src/main/java/com/polymarket/clob/example/DepositWalletOnboardAndTradeExample.java \
       src/main/java/com/polymarket/clob/example/OnboarderExample.java
```

- [ ] **Step 2: 改类名 + 私有构造函数名**

文件 `src/main/java/com/polymarket/clob/example/OnboarderExample.java`，将两处 `DepositWalletOnboardAndTradeExample` 改为 `OnboarderExample`：

```java
public final class OnboarderExample {

    private OnboarderExample() {}
```

- [ ] **Step 3: 验证仓库内零残留引用**

```bash
grep -rn "DepositWalletOnboardAndTradeExample" src/ pom.xml
```

Expected: 输出为空（README.md 与 CHANGELOG.md / docs/superpowers/ 的引用在后续 Task 中处理；本步只检查 `src/` 与 `pom.xml`）。

- [ ] **Step 4: 编译通过**

```bash
mvn -q -DskipTests compile
```

Expected: `BUILD SUCCESS`，无任何 javac 错误。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/polymarket/clob/example/OnboarderExample.java
git commit -m "$(cat <<'EOF'
refactor(example): DepositWalletOnboardAndTradeExample → OnboarderExample

强化定位：example 包内将出现两份 onboard + trade 演示，
本文件只演示"Onboarder.run() 一行调用"的高层用法，重命名以区别
即将新增的原语级 FullOnboardAndTradeExample。

Breaking: mvn -Dexec.mainClass=...DepositWalletOnboardAndTradeExample
的脚本需改为 -Dexec.mainClass=...OnboarderExample
EOF
)"
```

---

### Task 2: 创建 `FullOnboardAndTradeExample.java`

**Files:**
- Create: `src/main/java/com/polymarket/clob/example/FullOnboardAndTradeExample.java`

- [ ] **Step 1: 创建文件**

写入完整内容到 `src/main/java/com/polymarket/clob/example/FullOnboardAndTradeExample.java`：

```java
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
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        // ---- 解析环境变量 ----
        String pk = System.getenv("PK");
        if (pk == null || pk.isBlank()) {
            throw new IllegalStateException("set PK env var (EOA private key)");
        }
        String rpcUrl = Optional.ofNullable(System.getenv("RPC_URL")).orElse(DEFAULT_RPC_URL);
        String tokenIdStr = System.getenv("TOKEN_ID");
        String clobUrl = Optional.ofNullable(System.getenv("CLOB_API_URL")).orElse(DEFAULT_CLOB_URL);
        String envApiKey = System.getenv("CLOB_API_KEY");
        String envSecret = System.getenv("CLOB_SECRET");
        String envPassphrase = System.getenv("CLOB_PASS_PHRASE");

        Signer eoa = LocalSigner.fromPrivateKeyHex(pk);
        HttpClient http = HttpClient.newHttpClient();

        printSection(" Polymarket Deposit Wallet Onboard + Trade Test (Java, primitive-level)");
        System.out.println("EOA:     " + eoa.address().toHex());
        System.out.println("Chain:   Polygon (" + CHAIN_ID + ")");
        if (tokenIdStr == null || tokenIdStr.isBlank()) {
            System.out.println("Mode:    onboard only (TOKEN_ID not set, will skip step 7)");
        } else {
            System.out.println("Mode:    onboard + trade (token=" + tokenIdStr + ")");
        }

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

        // ---- Step 3: Ensure Gamma profile ----
        step(3, "Ensure Gamma profile (idempotent)");
        gamma.ensureProfile(session, eoa.address(), wallet).get();
        System.out.println("    ✅ profile ensured");

        // ---- Step 4: Deploy if needed ----
        DepositWalletRelayer relayer = new DepositWalletRelayer(RELAYER_HOST, http, session);
        if (deployed) {
            step(4, "Wallet already deployed — skip");
        } else {
            step(4, "Deploy wallet via WALLET-CREATE");
            String txId = relayer.submitWalletCreate(eoa.address(), PolymarketContracts.FACTORY);
            System.out.println("    txnID=" + txId);
            RelayerTxResult result = relayer.waitForTx(txId, RELAYER_POLL_INTERVAL, RELAYER_MAX_ATTEMPTS);
            System.out.println("    state=" + result.state() + " hash=" + result.txHash());
        }

        // ---- Step 5: Approvals batch ----
        step(5, "Check allowances and approve missing");
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

        // ---- Step 6: CLOB API credentials ----
        step(6, "Get CLOB L2 API credentials");
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

        // ---- Step 7: Test order (optional) ----
        if (tokenIdStr == null || tokenIdStr.isBlank()) {
            step(7, "(skipped — TOKEN_ID not set)");
            printSection(" ✅ Onboarding complete (no TOKEN_ID, order step skipped)");
            return;
        }

        step(7, "Place test order (token=" + tokenIdStr + ")");
        BigInteger usdcBalance = reads.erc20BalanceOf(PolymarketContracts.USDC_E, wallet).get();
        System.out.println("    Deposit wallet USDC.e balance: " + usdcBalance);
        if (usdcBalance.compareTo(BigInteger.valueOf(1_000_000L)) < 0) {
            System.out.println("    ⚠️  Wallet has < 1 USDC — order will likely be rejected by CLOB.");
            System.out.println("       Send some USDC.e to " + wallet.toHex() + " and re-run.");
        }

        try (ClobClient base = ClobClient.builder()
                .endpoint(URI.create(clobUrl))
                .chainId(CHAIN_ID)
                .httpClient(http)
                .build();
             AuthenticatedClobClient client = base.authenticate(
                     eoa, SignatureType.POLY_1271, wallet, creds)) {
            LimitOrderArgsV2 order = LimitOrderArgsV2.builder()
                    .tokenId(new BigInteger(tokenIdStr))
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

    /** TS 端 {@code pad(eoa, {size: 32})} 的等价实现：左补 12 字节零，得到 32B 工厂派生 id。 */
    private static String leftPadEoaTo32(Address eoa) {
        return "0x" + "0".repeat(24) + eoa.toHex().substring(2);
    }
}
```

- [ ] **Step 2: 编译通过**

```bash
mvn -q -DskipTests compile
```

Expected: `BUILD SUCCESS`，无任何 javac 错误（重点验证：`ClobClient.builder().httpClient(...).build()` / `base.authenticate(eoa, POLY_1271, wallet, creds)` / `client.createAndPostLimitOrderV2(...)` / `reads.erc20BalanceOf(...)` 都是真实存在的公共 API）。

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/polymarket/clob/example/FullOnboardAndTradeExample.java
git commit -m "$(cat <<'EOF'
feat(example): 新增 FullOnboardAndTradeExample（原语级 7 步）

对照 TS clob-client-v2/examples/account/fullOnboardAndTrade.ts，
直接组合 GammaClient / Web3jEvmRpcClient / DepositWalletReads /
ApprovalPlanner / BatchEip712 / DepositWalletRelayer / AuthApi /
AuthenticatedClobClient，不经过 Onboarder 黑盒。

用途：在某一步失败时按文件逐步定位、给 Polymarket 团队提供
日志佐证。"一键跑通"用 OnboarderExample。
EOF
)"
```

---

### Task 3: 更新 `README.md` 4 处引用

**Files:**
- Modify: `README.md`（行 94、447、463、479）

- [ ] **Step 1: 更新行 94（章节内联链接）**

将：

```markdown
完整端到端 + 下单见 `src/main/java/com/polymarket/clob/example/DepositWalletOnboardAndTradeExample.java`。
```

改为：

```markdown
完整端到端 + 下单见 `src/main/java/com/polymarket/clob/example/FullOnboardAndTradeExample.java`（原语级 7 步，排障/学习首选）或 `OnboarderExample.java`（一键 `Onboarder.run()`）。
```

- [ ] **Step 2: 更新行 447 附近的 `mvn exec:java` 命令**

先读取确认：

```bash
sed -n '443,452p' README.md
```

定位到这一行：

```
mvn -q exec:java -Dexec.mainClass=com.polymarket.clob.example.DepositWalletOnboardAndTradeExample
```

改为两行（保留原行的前后空行/上下文）：

```
mvn -q exec:java -Dexec.mainClass=com.polymarket.clob.example.OnboarderExample
mvn -q exec:java -Dexec.mainClass=com.polymarket.clob.example.FullOnboardAndTradeExample
```

- [ ] **Step 3: 更新行 463 附近的迁移表**

先读取确认：

```bash
sed -n '460,468p' README.md
```

定位到这一行（在 v1 → v2 迁移表中）：

```
| `EndToEndOnboardingExample` | `DepositWalletOnboardAndTradeExample` |
```

改为：

```
| `EndToEndOnboardingExample` | `OnboarderExample`（高层）/ `FullOnboardAndTradeExample`（原语级） |
```

- [ ] **Step 4: 更新行 479 附近的 example 总表**

先读取确认：

```bash
sed -n '475,483p' README.md
```

定位到：

```
| `DepositWalletOnboardAndTradeExample` | 完整 Deposit Wallet 冷启动流水线（v2） | `PK` `RPC_URL` `TOKEN_ID` |
```

改为两行：

```
| `OnboarderExample` | 一键 Onboarder 流水线（v2） | `PK` `RPC_URL` `TOKEN_ID` |
| `FullOnboardAndTradeExample` | 原语级 7 步 onboard + trade（排障样板） | `PK` `RPC_URL` `TOKEN_ID` `CLOB_API_URL` `CLOB_API_KEY` `CLOB_SECRET` `CLOB_PASS_PHRASE` |
```

- [ ] **Step 5: 验证 README 中已无旧名残留**

```bash
grep -n "DepositWalletOnboardAndTradeExample" README.md
```

Expected: 输出为空。

- [ ] **Step 6: 提交**

```bash
git add README.md
git commit -m "docs(readme): 同步 OnboarderExample / FullOnboardAndTradeExample 引用"
```

---

### Task 4: `CHANGELOG.md` 新增 unreleased 条目

**Files:**
- Modify: `CHANGELOG.md`（在 `## 2.0.0 — 2026-05-07` 之上插入新段）

- [ ] **Step 1: 在文件顶部 `# Changelog` 标题之下、`## 2.0.0` 之上插入**

```markdown
## Unreleased

### Breaking changes

- `example.DepositWalletOnboardAndTradeExample` 重命名为 `example.OnboarderExample`。调用方 `mvn exec:java -Dexec.mainClass=...DepositWalletOnboardAndTradeExample` 的脚本需改为 `...OnboarderExample`。

### Added

- `example.FullOnboardAndTradeExample`：原语级 7 步 onboard + trade 演示，对照 TS `clob-client-v2/examples/account/fullOnboardAndTrade.ts`。直接组合 `GammaClient` / `DepositWalletReads` / `ApprovalPlanner` / `BatchEip712` / `DepositWalletRelayer` / `AuthApi` / `AuthenticatedClobClient`，每步显式打印 `txnID` / `state` / `hash` / 响应 JSON，作为生产排障样板。
```

- [ ] **Step 2: 提交**

```bash
git add CHANGELOG.md
git commit -m "docs(changelog): unreleased — example 重命名 + FullOnboardAndTradeExample 新增"
```

---

### Task 5: 全套回归（编译 + 单测）

**Files:** 无文件改动；只跑命令验证。

- [ ] **Step 1: 编译**

```bash
mvn -q -DskipTests compile
```

Expected: `BUILD SUCCESS`。

- [ ] **Step 2: 单测**

```bash
mvn -q test
```

Expected: `BUILD SUCCESS`，全部测试通过。**预期不会**有测试因为 `DepositWalletOnboardAndTradeExample` 的重命名而失败——既有 80 个测试文件中没有任何一个 import 或 string-mention 这个类（重命名前已确认）。

- [ ] **Step 3: 仓库整体 grep（防御性）**

```bash
grep -rn "DepositWalletOnboardAndTradeExample" .
```

Expected: 仅出现在 `docs/superpowers/specs/2026-05-07-deposit-wallet-onboarding-design.md` / `docs/superpowers/plans/2026-05-07-deposit-wallet-onboarding-plan.md`（旧 spec/plan 历史快照，按写作时点保留，**不修改**）；以及 `CHANGELOG.md` 的 v2.0.0 旧段（描述当时新增）和 Unreleased 段（描述本次重命名）。**不应**出现在 `src/`、`pom.xml`、`README.md`。

- [ ] **Step 4: 不需要提交**（只是验证）

---

## 完成后建议（用户手动）

以下场景由用户在交付后真链冒烟（**非**本计划范围内）：

1. 干净 EOA、不设 `TOKEN_ID`：跑完 6 步 + 步骤 7 跳过；EOA 链上消耗 1 笔部署交易 + 1 笔 batch（含最多 13 笔 inner approval calls）。
2. 同一 EOA 重入：步骤 4 / 5 都打印 skip；**无**新链上交易。
3. 完整 onboard + trade（deposit wallet ≥ 1 USDC.e、`TOKEN_ID` 给定）：7 步全跑，stdout 末尾 `Response: {orderID: ..., status: ...}`。
4. `CLOB_API_KEY` / `CLOB_SECRET` / `CLOB_PASS_PHRASE` 三件套全给：步骤 6 打印 `Using existing creds from .env`，**无** L1 签名 / `/auth/api-key` HTTP 调用。
5. 缺 `PK`：立即抛 `IllegalStateException("set PK env var (EOA private key)")`，退出码 = 1。
6. 错的 `RPC_URL`：步骤 2 抛异常，stderr 打印 stacktrace，`echo $?` = 1。
7. `OnboarderExample` 仍可运行：`mvn -q exec:java -Dexec.mainClass=com.polymarket.clob.example.OnboarderExample` 行为与重命名前一致。
