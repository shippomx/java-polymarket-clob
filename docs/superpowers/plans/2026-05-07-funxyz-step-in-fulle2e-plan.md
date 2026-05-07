# Fun.xyz Step in FullOnboardAndTradeExample Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `FullOnboardAndTradeExample.run()` 中插入新的 Step 3 调用 `FunxyzClient.getDepositAddresses(...)` 打印 fun.xyz 四条链入金中转地址，原 Step 3-7 重编为 4-8。

**Architecture:** 单文件改动。在现有 Step 2（派生 wallet）之后、Step 3（ensure profile）之前插入新 step；机械替换 7 处 `step(N, ...)` 调用 + 1 处 `[N/7]` 格式串到 `[N/8]`；新增 3 行 `funxyz/` 包 import；不动现有 WIP（硬编码 PK / 注释 / helper）。

**Tech Stack:** Java 17, `com.polymarket.clob.funxyz.{FunxyzClient, FunxyzConfig, DepositAddresses}`（已落地于 commit `5d8fe7d`），Maven。

**Spec:** `docs/superpowers/specs/2026-05-07-funxyz-step-in-fulle2e-design.md`

---

## File Structure

| 文件 | 创建/修改 | 职责 |
|---|---|---|
| `src/main/java/com/polymarket/clob/example/FullOnboardAndTradeExample.java` | 修改 | 加 3 行 import + 插入新 Step 3 代码块 + 7 处 `step()` 数字位移 + 1 处格式串改 `[N/8]` |

**唯一一个文件被触及。** 测试套件不动；README / CHANGELOG 不动；example 类项目里不写单测（与 `OnboarderExample` / `BuilderExample` 等惯例一致）。

---

## Task 1: 嵌入新 Step 3 + 步骤编号位移

**Files:**
- Modify: `src/main/java/com/polymarket/clob/example/FullOnboardAndTradeExample.java`

整个改动一次提交。理由：单文件、机械改动、彼此互相依赖（先位移再插入会导致编号断档；先插入再位移会导致重复编号）—— 拆开提交反而让中间状态产生迷惑。

### Step 1: 备份当前 git diff（识别 WIP，确保不污染）

- [ ] **运行**

```bash
cd /Users/bmtaka/Downloads/java-polymarket-clob
git status --short
git diff --stat src/main/java/com/polymarket/clob/example/FullOnboardAndTradeExample.java
```

期望输出：

```
 M src/main/java/com/polymarket/clob/example/FullOnboardAndTradeExample.java
?? docs/auth-credentials.md
```

且 diff stat 显示约 18 行变更（这些是已存在的 WIP，**不要碰**）。

### Step 2: 加 3 行 funxyz/ import

文件顶部 import 块。在现有最后一个 `import com.polymarket.clob.deposit.SignedBatch;` 这一行之**后**插入（保持包字典序）：

- [ ] **编辑文件**

找到现有的：

```java
import com.polymarket.clob.deposit.RelayerTxResult;
import com.polymarket.clob.deposit.SignedBatch;
import com.polymarket.clob.gamma.GammaClient;
```

改为：

```java
import com.polymarket.clob.deposit.RelayerTxResult;
import com.polymarket.clob.deposit.SignedBatch;
import com.polymarket.clob.funxyz.DepositAddresses;
import com.polymarket.clob.funxyz.FunxyzClient;
import com.polymarket.clob.funxyz.FunxyzConfig;
import com.polymarket.clob.gamma.GammaClient;
```

### Step 3: 插入新 Step 3 代码块

在 `run()` 方法体内，定位到这两行：

```java
        // ---- Step 3: Ensure Gamma profile ----
        step(3, "Ensure Gamma profile (idempotent)");
```

在 `// ---- Step 3: Ensure Gamma profile ----` 这一行**之前**插入：

- [ ] **编辑文件**

```java
        // ---- Step 3: Fetch fun.xyz on-ramp deposit addresses ----
        step(3, "Fetch fun.xyz on-ramp deposit addresses");
        FunxyzClient funxyz = new FunxyzClient(FunxyzConfig.builder().build());
        DepositAddresses funding = funxyz.getDepositAddresses(eoa.address(), wallet).get();
        System.out.println("    EVM (Polygon):  " + funding.evm().toHex());
        System.out.println("    Solana:         " + funding.solana());
        System.out.println("    Tron:           " + funding.tron());
        System.out.println("    BTC (segwit):   " + funding.btcSegwit());

```

注意末尾留一个空行，与现有 step 之间的视觉间距一致。

### Step 4: 把原 Step 3 重命名为 Step 4

- [ ] **编辑文件**

把：

```java
        // ---- Step 3: Ensure Gamma profile ----
        step(3, "Ensure Gamma profile (idempotent)");
```

改为：

```java
        // ---- Step 4: Ensure Gamma profile ----
        step(4, "Ensure Gamma profile (idempotent)");
```

### Step 5: 把原 Step 4 重命名为 Step 5（两个分支都改）

- [ ] **编辑文件**

`run()` 中现有：

```java
        // ---- Step 4: Deploy if needed ----
        DepositWalletRelayer relayer = new DepositWalletRelayer(RELAYER_HOST, http, session);
        if (deployed) {
            step(4, "Wallet already deployed — skip");
        } else {
            step(4, "Deploy wallet via WALLET-CREATE");
```

改为：

```java
        // ---- Step 5: Deploy if needed ----
        DepositWalletRelayer relayer = new DepositWalletRelayer(RELAYER_HOST, http, session);
        if (deployed) {
            step(5, "Wallet already deployed — skip");
        } else {
            step(5, "Deploy wallet via WALLET-CREATE");
```

### Step 6: 把原 Step 5 重命名为 Step 6

- [ ] **编辑文件**

把：

```java
        // ---- Step 5: Approvals batch ----
        step(5, "Check allowances and approve missing");
```

改为：

```java
        // ---- Step 6: Approvals batch ----
        step(6, "Check allowances and approve missing");
```

### Step 7: 把原 Step 6 重命名为 Step 7

- [ ] **编辑文件**

把：

```java
        // ---- Step 6: CLOB API credentials ----
        step(6, "Get CLOB L2 API credentials");
```

改为：

```java
        // ---- Step 7: CLOB API credentials ----
        step(7, "Get CLOB L2 API credentials");
```

### Step 8: 把原 Step 7 重命名为 Step 8（两个分支都改）

- [ ] **编辑文件**

把：

```java
        // ---- Step 7: Test order (optional) ----
        if (tokenIdStr == null || tokenIdStr.isBlank()) {
            step(7, "(skipped — TOKEN_ID not set)");
            printSection(" ✅ Onboarding complete (no TOKEN_ID, order step skipped)");
            return;
        }

        step(7, "Place test order (token=" + tokenIdStr + ")");
```

改为：

```java
        // ---- Step 8: Test order (optional) ----
        if (tokenIdStr == null || tokenIdStr.isBlank()) {
            step(8, "(skipped — TOKEN_ID not set)");
            printSection(" ✅ Onboarding complete (no TOKEN_ID, order step skipped)");
            return;
        }

        step(8, "Place test order (token=" + tokenIdStr + ")");
```

### Step 9: 改 `step()` helper 的格式串 `[N/7]` → `[N/8]`

- [ ] **编辑文件**

把：

```java
    private static void step(int n, String label) {
        System.out.println();
        System.out.println("[" + n + "/7] " + label);
    }
```

改为：

```java
    private static void step(int n, String label) {
        System.out.println();
        System.out.println("[" + n + "/8] " + label);
    }
```

### Step 10: 静态校验：编号无残留

- [ ] **运行**

```bash
cd /Users/bmtaka/Downloads/java-polymarket-clob
echo "=== 还有没有 step(3 留着旧含义的? (新 step(3 应该是 fun.xyz, 不是 ensure profile) ==="
grep -n "step(3," src/main/java/com/polymarket/clob/example/FullOnboardAndTradeExample.java
echo
echo "=== 仍然写着 [N/7] 的格式串? (应为零结果) ==="
grep -n '"\[" + n + "/7\]"' src/main/java/com/polymarket/clob/example/FullOnboardAndTradeExample.java
echo
echo "=== 7 行 step(N,...) 调用 (新流程应有 9 行: 1,2,3,4,5,5,6,7,8,8 共 10 行) ==="
grep -n "step([1-9]," src/main/java/com/polymarket/clob/example/FullOnboardAndTradeExample.java
```

期望：
- `step(3,` → 1 行（且 label 是 "Fetch fun.xyz on-ramp deposit addresses"）
- `[N/7]` → **零**结果
- `step(N,` 总共 9 行：step(1), step(2), step(3), step(4), step(5)×2, step(6), step(7), step(8)×2

### Step 11: 编译

- [ ] **运行**

```bash
cd /Users/bmtaka/Downloads/java-polymarket-clob && mvn -o compile -q
```

期望：`BUILD SUCCESS`，无 warning。

### Step 12: 完整测试套件回归

- [ ] **运行**

```bash
cd /Users/bmtaka/Downloads/java-polymarket-clob && mvn -o test -q 2>&1 | tail -8
```

期望：`Tests run: 451, Failures: 0, Errors: 0, Skipped: 1`，BUILD SUCCESS。

（example 类不在测试图谱里 —— 这一步只确认本改动没意外破坏其它测试。）

### Step 13: git diff 边界校验

- [ ] **运行**

```bash
cd /Users/bmtaka/Downloads/java-polymarket-clob
git diff --stat
```

期望输出形如：

```
 .../FullOnboardAndTradeExample.java | <数字> ++++++++++++-------
 1 file changed, ...
```

**关键校验**：
- 只 `FullOnboardAndTradeExample.java` 一个文件被改
- 不要出现 `docs/auth-credentials.md`（untracked，应留 untracked）
- 不要出现其他源码文件

如果发现额外文件被改，**STOP** —— 不要 stage / commit，回头核查并恢复。

### Step 14: 提交

- [ ] **运行**

```bash
cd /Users/bmtaka/Downloads/java-polymarket-clob
git add src/main/java/com/polymarket/clob/example/FullOnboardAndTradeExample.java
git commit -m "$(cat <<'EOF'
feat(example): FullOnboardAndTradeExample 嵌入 fun.xyz 入金地址查询步骤

在派生 Deposit Wallet 之后(原 Step 2 后),新增 Step 3
Fetch fun.xyz on-ramp deposit addresses,打印 EVM/Solana/Tron/BTC 四条链
固定映射的入金中转地址。原 Step 3-7 重编为 4-8。
fun.xyz 失败硬 fail,经默认 stack trace 路径报告(与其他 step 一致)。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Step 15: 查看 commit 状态

- [ ] **运行**

```bash
cd /Users/bmtaka/Downloads/java-polymarket-clob
git log -1 --stat
git status --short
```

期望：
- 新 commit 上仅 `FullOnboardAndTradeExample.java` 一个文件
- `git status --short` 仍显示 `?? docs/auth-credentials.md`（pre-existing WIP，不应消失）

---

## 手动验证（可选 —— 留给执行者本地确认，无 CI 性质）

以下不是任务的一部分，是给完成后的人做的烟雾测试。subagent 不要在这里跑（需要 EOA 私钥环境变量，subagent 不应擅自跑 onboarding 流程）。

```bash
# 用现有 WIP 里硬编码的 PK 跑一次
cd /Users/bmtaka/Downloads/java-polymarket-clob
mvn -o exec:java -Dexec.mainClass=com.polymarket.clob.example.FullOnboardAndTradeExample 2>&1 | head -30
```

肉眼对账：
- ☐ 表头 `[3/8]` 出现，原 `[3/7]` 全部消失
- ☐ `[3/8] Fetch fun.xyz on-ramp deposit addresses` 之后出现 4 行：EVM / Solana / Tron / BTC
- ☐ EVM 行的值是合法 0x 前缀 EVM 地址
- ☐ 其余三链非空字符串
- ☐ Step 4-8 顺次执行无回归

如失败：

| 现象 | 排查 |
|---|---|
| Step 3 `FunxyzException: funxyz request failed` | 网络 / 防火墙；查 `cause.getMessage()` |
| Step 3 `FunxyzException` `httpStatus == 401` | fun.xyz 旋转了 public apiKey；改 `FunxyzConfig.builder().apiKey(...).build()` 注入新 key |
| Step 3 `eoa blocked by fun.xyz` | 该 EOA 上 fun.xyz 黑名单；换 EOA 测 |

---

## Self-Review

- ✅ **Spec coverage**：spec §1.2 范围 → Step 2-3,9（imports / 新 step / 编号位移）；spec §2.2 代码 → Step 3；spec §2.3 8 处替换 → Step 4-9；spec §3 错误处理 → 自然冒泡，无单独任务（设计如此）；spec §4.1 静态验证 → Step 11-12；spec §4.2 手动运行 → 文档末尾「手动验证」段
- ✅ **Placeholder scan**：每步均给确切代码 / 命令 / 期望输出；无 TBD / TODO / "implement later"
- ✅ **Type 一致性**：`FunxyzClient` / `FunxyzConfig` / `DepositAddresses` 三个类名跨 Step 2 import 与 Step 3 用法一致；`FunxyzConfig.builder().build()` / `getDepositAddresses(eoa, recipient)` / `funding.evm() / .solana() / .tron() / .btcSegwit()` 这些 API 形态与上游 spec `2026-05-07-funxyz-onramp-v2-design.md` §3 一致
- ✅ **范围聚焦**：单文件改动，不需要拆分多个 plan
