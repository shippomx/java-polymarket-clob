# 把 fun.xyz 入金地址步骤嵌入 FullOnboardAndTradeExample

> **状态**：设计稿，待实现
> **作者**：taka@noahbase.com
> **日期**：2026-05-07
> **参考**：
> - 当前文件：`src/main/java/com/polymarket/clob/example/FullOnboardAndTradeExample.java`（282 行）
> - 上游基础设施：`src/main/java/com/polymarket/clob/funxyz/`（已落地，commit `5d8fe7d`）
> - fun.xyz 协议事实：`docs/superpowers/specs/2026-05-07-funxyz-onramp-v2-design.md` §2

---

## 1. 目标与范围

### 1.1 目标

`FullOnboardAndTradeExample` 是 Polymarket Deposit Wallet 端到端原语级调试器。当前 7 步覆盖 onboarding + 下单。新增一步 **fun.xyz 法币入金地址查询** —— 跑完即可同时拿到链上 maker wallet（Polymarket Deposit Wallet）和法币入金中转地址（fun.xyz EVM/Solana/Tron/BTC 四条链），调试时一站式对账。

### 1.2 范围

**包含：**
- 在 `run()` 中现有 Step 2（派生 wallet）和 Step 3（ensure profile）之间，**插入新 Step 3** "Fetch fun.xyz on-ramp deposit addresses"
- 原 Step 3-7 重编为 4-8（7 处 `step(N, ...)` 调用 + `step()` 内部 `[N/7]` 字符串改成 `[N/8]`）
- 新增 import：`com.polymarket.clob.funxyz.{FunxyzClient, FunxyzConfig, DepositAddresses}`
- 新 step 输出 4 行打印（4 条链地址，缩进与 Step 2 对齐）

**不包含：**
- 修改流程顺序、环境变量约定、主 try-catch
- 改动现有 WIP 调试遗留（硬编码 PK / TOKEN_ID / drpc URL、注释掉的余额警告、ClobApiException body 打印、`parseTokenId` helper —— 全部保留原样）
- 新增测试（example 类项目里不写单测）
- 修改 README / CHANGELOG（example 微调不需要）

### 1.3 关键决策摘要

| # | 决策 | 选择 |
|---|---|---|
| Q1 | 插入位置 | 独立新 Step 3，原 3-7 重编 4-8 |
| Q2 | 打印哪些链 | 全部 4 条链（EVM/Solana/Tron/BTC） |
| Q3 | 失败处理 | 硬 fail，跳 main catch（与其他 step 一致） |
| Q4 | WIP 清理 | 不动现有 WIP |

---

## 2. 改动详情

### 2.1 import 增量

在文件顶部 import 块加入（位置：与现有 `com.polymarket.clob.deposit.*` import 簇相邻 / 字典序）：

```java
import com.polymarket.clob.funxyz.DepositAddresses;
import com.polymarket.clob.funxyz.FunxyzClient;
import com.polymarket.clob.funxyz.FunxyzConfig;
```

不引入 `FunxyzException`：异常自然冒泡到顶层 `Throwable t` catch，不需要类型识别。

### 2.2 新 Step 3 代码

插入位置：约第 137 行 `// ---- Step 3: Ensure Gamma profile ----` 注释 **之前**（行号以执行时实际文件为准 —— 锚定该注释字符串）。

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

设计要点：
- **`FunxyzConfig.builder().build()`** 用全部默认值（默认 host / Polymarket public apiKey / 新建 HttpClient / 10s timeout）。
- **不复用** `run()` 局部的 `http` 变量。fun.xyz 是独立第三方服务，让其 client 隔离不污染 Gamma/Relayer 共享的连接池；原语级演示也不该掩盖第三方边界。
- **`.get()`** 同步阻塞直至完成。错误以 `ExecutionException(cause = FunxyzException)` 抛出，`run() throws Exception` 自然冒泡。
- **缩进格式** 与 Step 2 一致（4 空格 + `<标签>:` + 值），保持视觉规整。

### 2.3 步骤编号位移

| 当前行（约） | 当前 | 改为 |
|---|---|---|
| 137 | `step(3, "Ensure Gamma profile (idempotent)")` | `step(4, "Ensure Gamma profile (idempotent)")` |
| 142 | `step(4, "Wallet already deployed — skip")` | `step(5, "Wallet already deployed — skip")` |
| 145 | `step(4, "Deploy wallet via WALLET-CREATE")` | `step(5, "Deploy wallet via WALLET-CREATE")` |
| 153 | `step(5, "Check allowances and approve missing")` | `step(6, "Check allowances and approve missing")` |
| 178 | `step(6, "Get CLOB L2 API credentials")` | `step(7, "Get CLOB L2 API credentials")` |
| 207 | `step(7, "(skipped — TOKEN_ID not set)")` | `step(8, "(skipped — TOKEN_ID not set)")` |
| 212 | `step(7, "Place test order (token=" + tokenIdStr + ")")` | `step(8, "Place test order (token=" + tokenIdStr + ")")` |
| 248 | `System.out.println("[" + n + "/7] " + label);` | `System.out.println("[" + n + "/8] " + label);` |

8 处机械替换。

### 2.4 不动的代码

- 文件顶部网络常量、测试参数、`main()` 顶层 try-catch（含 ClobApiException unwrap）、`parseTickSize` / `leftPadEoaTo32` / `parseTokenId` helpers
- 现有 WIP 改动（硬编码 PK / TOKEN_ID / `drpc.org`、注释掉的余额警告、`ClobApiException` body 打印、`parseTokenId` helper）
- `printSection` 输出文案

---

## 3. 错误处理

### 3.1 失败模式

| 触发 | 调用方观察 | 流程 |
|---|---|---|
| 网络拒绝（断网 / 防火墙） | `FunxyzException`，`isTransport() == true`，`httpStatus == -1` | 顶层 catch → stack trace + `exit(1)` |
| HTTP 4xx / 5xx | `FunxyzException`，`httpStatus = 实际值` | 同上 |
| 风控 (`blocked: true`) | `FunxyzException("eoa blocked by fun.xyz", 200)` | 同上，stack trace 含 message |
| 响应 schema 异常（缺字段 / 非合法 EVM 地址） | `FunxyzException`，`httpStatus == 200` | 同上 |

### 3.2 现有 unwrap 逻辑不影响 fun.xyz

`main()` 里现有的 ClobApiException unwrap：

```java
while (cause != null) {
    if (cause instanceof com.polymarket.clob.exception.ClobApiException e) {
        System.err.println("    upstream body: " + e.getBody());
        break;
    }
    cause = cause.getCause();
}
```

只识别 `ClobApiException`，对 `FunxyzException` 走默认 `printStackTrace` 路径 —— 完整堆栈足够调试，不需要新增 funxyz-specific 解包逻辑。

---

## 4. 验证

### 4.1 静态

- `mvn -o compile -q` → `BUILD SUCCESS`
- `mvn -o test -q` → `Tests run: 451, Failures: 0, Errors: 0, Skipped: 1`（example 不在测试图谱里）
- `git diff src/main/java/com/polymarket/clob/example/FullOnboardAndTradeExample.java` → 无外溢改动

### 4.2 运行（手动）

```bash
mvn exec:java -Dexec.mainClass=com.polymarket.clob.example.FullOnboardAndTradeExample
```

预期输出片段：

```
[3/8] Fetch fun.xyz on-ramp deposit addresses
    EVM (Polygon):  0x4C741213d8519429002ab3E69DE9620fb9b48C69
    Solana:         CobugN8o4CnNVYL9jj7NGRDrdG2hJxAwMiPNZug7N6Pk
    Tron:           TN4Vfn2wjVZGM8z8oy8MFLSwcW36bs3418
    BTC (segwit):   bc1q7hum6lx3rad7xzsfjxrle4ryk6ev527pk0xzws

[4/8] Ensure Gamma profile (idempotent)
    ✅ profile ensured

...

[8/8] Place test order (token=...)
    Response: ...
```

肉眼校验：
- ☐ 表头从 `[3/8]` 起，所有 step 标签都是 `/8`
- ☐ EVM 是合法 0x 前缀 EVM 地址
- ☐ 其他三条链非空
- ☐ Step 4-8 顺次执行无回归

### 4.3 失败场景（可选）

把 `FunxyzConfig.builder().baseUrl(URI.create("http://localhost:1")).build()` 临时塞进去（连接拒绝）→ 跑一次，预期 stack trace 含 `FunxyzException: funxyz request failed: ...`、`isTransport() == true`、`httpStatus == -1`，且执行在 Step 3 终止。改回再提交。

---

## 5. 风险

| 风险 | 缓解 |
|---|---|
| fun.xyz 默认 apiKey 被 Polymarket 旋转 → 4xx | 失败 stack trace 直接定位；用户可临时 `FunxyzConfig.builder().apiKey(...).build()` 注入自己的 key |
| 步骤编号失同步（漏改一处 `step(N, ...)`） | plan 任务里把所有 8 处替换列成 checklist；执行后 `grep -n "step([1-9]" + grep "[N]/7"` 校验无残留 |
| WIP 与本期改动冲突 | WIP 触及的 line（87-95 / 105-110 / 117-119 / 217-220 / 233 / 273-281）和本期改动的 line（137 前后 / 步骤编号位移）不重叠；spec 已明确不动 WIP |

---

## 6. 实施顺序

1. 文件顶部加 3 行 import
2. 第 137 行前插入新 Step 3 代码块
3. 全文替换 7 处 `step(N, ...)` 数字位移
4. 替换 `step()` 内的 `[N/7]` → `[N/8]`
5. `mvn -o compile -q` + `mvn -o test -q` 验证
6. 提交（一个 commit）
