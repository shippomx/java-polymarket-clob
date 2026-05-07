# Fun.xyz 法币入金地址封装（v2 新增 funxyz/ 包）

> **状态**：设计稿，待实现
> **作者**：taka@noahbase.com
> **日期**：2026-05-07
> **参考**：
> - HAR 反向工程：`/Users/bmtaka/Downloads/clob-client-v2/docs/new.har`（entry 612 = `POST api.fun.xyz/v1/eoa`）
> - 实测 curl 返回 245B JSON，已验证 fun.xyz 服务端按 `userId` 持久映射
> - 既有 `gamma/GammaClient.java`（同构对照）

---

## 1. 目标与范围

### 1.1 目标

在 `com.polymarket:clob-client:2.0.0` 中新增一个独立子包 `com.polymarket.clob.funxyz/`，封装 Polymarket 前端使用的 fun.xyz 法币入金接口 `POST https://api.fun.xyz/v1/eoa`，把"给定一个 EOA 取它在 fun.xyz 上的多链入金中转地址"做成一行 Java 调用。

### 1.2 范围

**包含：**

- 新包 `com.polymarket.clob.funxyz/`，5 个 final 类：`FunxyzClient` / `FunxyzConfig` / `DepositAddresses` / `FunxyzException` / `package-info`
- 单测套（JDK `HttpServer` 本地 stub，不引新依赖）
- 一条 env-var gated 集成测（默认跳过，本机 `FUNXYZ_IT=true mvn test` 才打外网）
- README 末尾 `## Fun.xyz 法币入金地址（v2）` 一小节
- 一个 example 类 `FunxyzAddressLookupExample.java`

**不包含：**

- `/v1/fops` 报价、Swapped widget URL 构造、入账轮询、KYC 状态查询 —— 后续如要做，独立新方法叠加到 `FunxyzClient`，与本期解耦
- 缓存、重试、并发限速 —— 由调用方按需自行包装
- 多 fun.xyz 账号管理 —— 一个 `FunxyzClient` 实例一个 apiKey，多账号使用方实例化多个 client
- 与 `onboard/`、`deposit/`、`chain/` 任何形式的相互依赖

### 1.3 关键决策摘要

| # | 决策 | 选择 |
|---|---|---|
| Q1 | 范围 | 仅封装 `/v1/eoa`，不做 widget URL / 编排 |
| Q2 | 包位置 | 新包 `com.polymarket.clob.funxyz/`，与 `onboard/ deposit/ chain/` 零耦合 |
| Q3 | API 形状 | 单方法 `getDepositAddresses(eoa, recipient)` → `DepositAddresses` record |
| Q4 | x-api-key 策略 | 内置默认（HAR 抓出的 Polymarket 公开 key）+ Builder 可覆盖 |
| Q5 | recipient 是否必传 | 必传，调用方自己算 Deposit Wallet 传入 —— 避免 funxyz/ 反向依赖 chain/deposit/ |
| Q6 | 整体类风格 | 镜像 `gamma/GammaClient`：单 final 类、`URI baseUrl + HttpClient` 注入、`CompletableFuture<>` |
| Q7 | 错误模型 | 单一异常 `FunxyzException(message, httpStatus)`，`httpStatus = -1` 表示传输错 |
| Q8 | `blocked: true` 处理 | 抛 `FunxyzException`（`isBlocked() == true`），不作为 `DepositAddresses` 字段 |

---

## 2. 协议事实速查（来自 HAR + 实测 curl）

```
URL              POST https://api.fun.xyz/v1/eoa
Auth             header x-api-key: <Polymarket public key, 26 字符>
                 默认值 = "Y53dikxXdT4E3afI1l8BMBSWgyhKvf65k6Dut1k6"
                 来源：polymarket.com 前端硬编码（非用户私密）
弱 CORS 头       origin:  https://polymarket.com
                 referer: https://polymarket.com/
content-type     application/json

请求体形状（606 B）:
{
  "userId":         "<EOA, 0x... 小写>",   ← 唯一影响返回的字段
  "recipientAddr":  "<目标钱包, 0x... 小写>", ← fun.xyz 仅记账,不影响返回地址
  "toChainId":      "137",
  "toTokenAddress": "0x2791bca1f2de4661ed88a30c99a7a9449aa84174",  ← Polygon USDC.e
  "clientMetadata": { ...UI state 占位骨架, 内容不被校验... }
}

实测响应（200, 245 B）:
{
  "depositAddr":   "0x...",          ← Polygon (EVM) 入金中转 EOA
  "solanaAddr":    "...",            ← Solana base58
  "tronAddr":      "T...",           ← Tron base58
  "btcAddrSegwit": "bc1q...",        ← Bitcoin segwit
  "blocked":       false             ← 风控黑名单标记
}

不变量:
- 同一 userId 永远返回同一组 4 链地址（fun.xyz 服务端持久映射）
- 不需要 cookie / Bearer，仅 x-api-key
- recipientAddr 不影响返回，但出现在请求体里以便 fun.xyz 记录"用户后续 USDC 到账要转给谁"
```

---

## 3. 包结构与组件

### 3.1 文件清单

```
src/main/java/com/polymarket/clob/funxyz/
    FunxyzClient.java
    FunxyzConfig.java
    DepositAddresses.java
    FunxyzException.java
    package-info.java
src/test/java/com/polymarket/clob/funxyz/
    FunxyzClientTest.java
    FunxyzClientIntegrationTest.java
src/main/java/com/polymarket/clob/example/
    FunxyzAddressLookupExample.java
```

每个类都是 final、无继承层级、不超过 200 行。

### 3.2 各类职责

| 类 | 职责 | 依赖 |
|---|---|---|
| `FunxyzClient` | 构造请求体 / 设置 4 个固定头 / 异步发起 / 解析 200 → record / 非 200 / blocked / IOException 全部抛 `FunxyzException` | `FunxyzConfig`、`java.net.http.HttpClient`、`com.polymarket.clob.http.JsonCodec`、`com.polymarket.clob.model.Address`、`DepositAddresses`、`FunxyzException` |
| `FunxyzConfig` | record(`URI baseUrl`, `String apiKey`, `HttpClient httpClient`, `Duration requestTimeout`) + 嵌套 `Builder`，`build()` 时 `apiKey` 默认 `DEFAULT_PUBLIC_API_KEY`、`baseUrl` 默认 `https://api.fun.xyz`、`httpClient` 默认 `HttpClient.newHttpClient()`、`requestTimeout` 默认 `Duration.ofSeconds(10)` | 仅 JDK + `java.net.http.HttpClient` |
| `DepositAddresses` | record(`Address evm`, `String solana`, `String tron`, `String btcSegwit`)。**不带 `blocked` 字段** —— blocked 走异常通道 | `com.polymarket.clob.model.Address` |
| `FunxyzException` | `RuntimeException` 派生。字段 `int httpStatus`。便利谓词：`isTransport()` (`httpStatus < 0`)、`isBlocked()` (固定 message 比较) | JDK |
| `package-info` | javadoc 解释包用途 / 与 `onboard/ deposit/` 关系 / fun.xyz 服务定位 | 无 |

### 3.3 公共 API（**唯一一个公共方法**）

```java
public final class FunxyzClient {

    public FunxyzClient(FunxyzConfig cfg);

    public CompletableFuture<DepositAddresses> getDepositAddresses(
            Address eoa,
            Address recipient
    );
}
```

参数校验：

- `eoa == null || recipient == null` → 同步抛 `IllegalArgumentException("eoa/recipient must not be null")`，**不进 future**
- 其它一切错误进 future，统一 `FunxyzException`

### 3.4 命名取舍

- 类名 `FunxyzClient`，不是 `FunXyzClient`：fun.xyz 域名小写，社区约定俗成
- 返回类型 `DepositAddresses`，不是 `FunxyzEoaResponse`：站在调用者视角而非协议视角
- `evm` 字段类型用 `model.Address`；`solana / tron / btcSegwit` 用 `String`：项目无 SOL/TRX/BTC value object，硬塞会越界

---

## 4. 数据流

```
FunxyzClient.getDepositAddresses(eoa, recipient)
        │
        │ 同步参数校验（null 检查）
        │
        │ 1. 构造请求体：Jackson ObjectNode
        │      userId         = eoa.toLowerHex()
        │      recipientAddr  = recipient.toLowerHex()
        │      toChainId      = "137"            ← 常量
        │      toTokenAddress = USDC_E_POLYGON   ← 常量
        │      clientMetadata = CLIENT_METADATA_STUB（写死 JSON 字符串）
        │
        │ 2. HttpRequest:
        │      uri      = cfg.baseUrl().resolve("/v1/eoa")
        │      method   = POST
        │      headers  = content-type / origin / referer / x-api-key
        │      timeout  = cfg.requestTimeout()
        │      body     = bodyJson
        │
        │ 3. cfg.httpClient().sendAsync(req, ofString())
        │
        │ 4. 解析:
        │      IOException / HttpTimeout    → FunxyzException(msg, -1, cause)
        │      status != 200                → FunxyzException(msg, status)
        │      JSON 解析失败                → FunxyzException(msg, 200, cause)
        │      blocked == true              → FunxyzException("eoa blocked by fun.xyz", 200)
        │      depositAddr 缺失/非 0x       → FunxyzException(msg, 200)
        │      otherwise                    → DepositAddresses(...)
        ▼
CompletableFuture<DepositAddresses>
```

### 4.1 请求体生成关键决定

- `clientMetadata` **必须存在**（缺整字段会 400）。我们发**最小空壳**，封进 `private static final String CLIENT_METADATA_STUB`：
  ```json
  {"id":"","startTimestampMs":0,"finalDollarValue":0,"latestQuote":null,"depositAddress":null,
   "initSettings":{"config":{"targetAsset":"0x","targetChain":"","targetAssetTicker":"","checkoutItemTitle":""}},
   "selectedSourceAssetInfo":{"address":"0x","symbol":"","chainId":"","iconSrc":null},
   "selectedPaymentMethodInfo":{"paymentMethod":"token_transfer","title":"QR Code Transfer","description":""}}
  ```
  不暴露成 builder 选项 —— 它对返回结果零影响，暴露只会让使用者迷惑。

- `toChainId / toTokenAddress` 同理硬编码常量：
  ```java
  private static final String TO_CHAIN_ID    = "137";
  private static final String USDC_E_POLYGON = "0x2791bca1f2de4661ed88a30c99a7a9449aa84174";
  ```

- `origin / referer` 必须发（fun.xyz 弱 CORS 检查），写死成常量，**不暴露成配置**：
  ```java
  private static final String ORIGIN  = "https://polymarket.com";
  private static final String REFERER = "https://polymarket.com/";
  ```

- 不附带 cookie / Bearer，`x-api-key` 是 fun.xyz 唯一鉴权。

### 4.2 响应解析

直接 Jackson `readTree`，按字段读：

```java
JsonNode root = mapper.readTree(body);
boolean blocked = root.path("blocked").asBoolean(false);
if (blocked) throw new FunxyzException("eoa blocked by fun.xyz", 200);

String depositAddr = root.path("depositAddr").asText("");
// 必须是 "0x" + 40 hex 字符 = 42 字符
if (!depositAddr.startsWith("0x") || depositAddr.length() != 42) {
    throw new FunxyzException("funxyz response missing or malformed depositAddr", 200);
}

return new DepositAddresses(
    Address.of(depositAddr),
    root.path("solanaAddr").asText(""),
    root.path("tronAddr").asText(""),
    root.path("btcAddrSegwit").asText("")
);
```

**不做 schema 严格校验**：fun.xyz 多返回字段忽略；solana/tron/btcSegwit 缺失就空字符串（调用方按需校验）。

### 4.3 不做的事

- ❌ **不缓存**：fun.xyz 服务端已是固定映射，本地缓存只会和"换 apiKey 后 pin 到旧地址"打架
- ❌ **不重试**：抛错让调用方决定，避免隐式重试触发 fun.xyz 风控
- ❌ **不复用 `HttpTransport`**：那个写死了 CLOB-specific 头，跨耦合不值

---

## 5. 错误模型

### 5.1 异常签名

```java
public final class FunxyzException extends RuntimeException {
    private final int httpStatus;

    public FunxyzException(String message, int httpStatus);
    public FunxyzException(String message, int httpStatus, Throwable cause);

    public int httpStatus();
    public boolean isTransport();   // httpStatus < 0
    public boolean isBlocked();     // message.equals("eoa blocked by fun.xyz")
}
```

`RuntimeException` 而非 checked —— 与 `GammaAuthException`、`RelayerTxFailedException`、`EvmRpcException` 一致。

### 5.2 失败分类表

| 触发条件 | message 范例 | httpStatus | cause |
|---|---|---|---|
| 传输错（DNS / TCP / TLS / IO） | `funxyz request failed: <cause msg>` | `-1` | `IOException` |
| Timeout | `funxyz request timed out after 10s` | `-1` | `HttpTimeoutException` |
| HTTP 4xx / 5xx | `funxyz POST /v1/eoa returned 401: <body 截前 500 字>` | 实际值 | 无 |
| 200 但 JSON 损坏 | `funxyz returned malformed JSON: <截前 200 字>` | `200` | Jackson 异常 |
| 200 且 `blocked: true` | `eoa blocked by fun.xyz` | `200` | 无 |
| 200 且 `depositAddr` 字段缺失 / 非合法 EVM 地址（"0x" + 40 hex） | `funxyz response missing or malformed depositAddr` | `200` | 无 |

### 5.3 异步语义

- 同步路径出错（`null` 参数）→ 直接 `throw IllegalArgumentException`，不进 future
- 异步路径任何错误 → `CompletionException(cause = FunxyzException)`，调用方 `.join()` / `.get()` 解包，与项目其它 client 一致

### 5.4 调用者收到的实际表现

```java
try {
    DepositAddresses addrs = client.getDepositAddresses(eoa, wallet).join();
} catch (CompletionException ce) {
    if (ce.getCause() instanceof FunxyzException fe) {
        if (fe.isBlocked())              { /* 风控 */ }
        else if (fe.isTransport())       { /* 网络 */ }
        else if (fe.httpStatus() >= 500) { /* 5xx */ }
        else if (fe.httpStatus() == 401) { /* apiKey 失效 */ }
        else                             { /* 其它 */ }
    }
}
```

### 5.5 日志

`FunxyzClient` 持有 `Logger log = LoggerFactory.getLogger(FunxyzClient.class)`：

- `INFO`：请求发出（`eoa=0x..., recipient=0x...`）
- `WARN`：失败时附 status + 截断 body
- **不记录 `apiKey`、不记录响应体里的地址明文**（PII / 敏感数据防漏）

---

## 6. 测试策略

### 6.1 单元测试 `FunxyzClientTest`

`mvn test` 默认跑，**不打外网**。技法：JDK 自带 `com.sun.net.httpserver.HttpServer` 起本地 stub 服务，把 `FunxyzConfig.baseUrl()` 指向 stub。**不引入 WireMock**（pom 里没有，不为单测加依赖）。

7 个用例：

| # | 场景 | 关键断言 |
|---|---|---|
| 1 | 200 + 正常 body | 4 字段非空，`evm` 是 `Address` 类型 |
| 2 | 请求体形状 | stub 收到 body：`userId / recipientAddr` 是参数小写形式；`toChainId="137"`、`toTokenAddress` 是 USDC.e；`clientMetadata` 存在 |
| 3 | 请求头 | `x-api-key / origin / referer / content-type` 全到位且值正确 |
| 4 | `blocked: true` | 抛 `FunxyzException`，`isBlocked() == true` |
| 5 | HTTP 401 | 抛 `FunxyzException`，`httpStatus() == 401`，message 含 status |
| 6 | HTTP 500 | 抛 `FunxyzException`，`httpStatus() == 500` |
| 7 | 响应 JSON 损坏 | 抛 `FunxyzException`，cause 是 Jackson 异常 |

外加 1 个参数校验单测：`null` eoa / `null` recipient → 同步 `IllegalArgumentException`（不进 future）。

### 6.2 集成测试 `FunxyzClientIntegrationTest`

打真实 `https://api.fun.xyz/v1/eoa`，用 `@EnabledIfEnvironmentVariable(named = "FUNXYZ_IT", matches = "true")` 默认跳过 —— CI 不跑、没网不挂。

唯一用例：用 HAR 里的 `EOA = 0x5f0fE471...` + `recipient = 0xB51b3627...`，断言 `evm` 等于 `0x4C741213d8519429002ab3E69DE9620fb9b48C69`。这是反向证明：fun.xyz 的固定映射没变 + 我们的请求形状没漂。

### 6.3 不写的测试

- ❌ 基于 mockito 的纯 mock 单测（stub server 更真实）
- ❌ Timeout 单测（让线程睡 10s 拖慢 CI 不值，timeout 路径靠类型系统兜住）
- ❌ 并发单测（`HttpClient` 自身 thread-safe，无新并发面）

---

## 7. README 与示例

### 7.1 README 增量

仓库根 `README.md` 现有"使用"一节末尾追加：

```markdown
### Fun.xyz 法币入金地址（v2）

```java
FunxyzClient client = new FunxyzClient(FunxyzConfig.builder().build());
DepositAddresses addrs = client
        .getDepositAddresses(eoa, depositWallet)
        .join();
String evm = addrs.evm().toString();
```

`apiKey` 默认是 Polymarket 前端公开 key，可用 `FunxyzConfig.builder().apiKey(...).build()` 覆盖为自有 fun.xyz key。`recipient` 用调用方已知的 Deposit Wallet 地址（`DepositWalletDerivation.predictWalletAddress(eoa)`）。
```

### 7.2 Example 类

`example/FunxyzAddressLookupExample.java`，~30 行：

```
读 PK from env
→ DepositWalletDerivation.predictWalletAddress(eoa)
→ FunxyzClient.getDepositAddresses(eoa, wallet)
→ println 4 个地址
```

跨包调用 `funxyz/` 与 `deposit/` 仅在 example 层发生，不污染主包边界。

---

## 8. 风险与缓解

| 风险 | 缓解 |
|---|---|
| Polymarket 旋转 fun.xyz public apiKey | `FunxyzConfig.Builder.apiKey(...)` 一行换；HAR 里抓的 key 写在常量上，注释指向"何处可重新抓" |
| fun.xyz 改 `/v1/eoa` schema | 集成测会先于业务报错；解析层用 `path(...).asText("")` 容错，可降级 |
| `clientMetadata` 校验变严 | 占位骨架抓的是 2026-05-07 HAR；如未来 fun.xyz 加字段校验，覆盖 stub 常量即可 |
| `origin / referer` 校验变严 | 同上，常量更新即可；当前实测从非 polymarket.com 域调用接受 |
| fun.xyz 风控误伤 | `blocked: true` 走异常，调用方能感知；不做静默降级 |

---

## 9. 实施顺序

1. 4 个 main 类骨架（FunxyzConfig / FunxyzException / DepositAddresses / FunxyzClient）+ package-info
2. 单测 `FunxyzClientTest` 7+1 个用例（HttpServer stub）
3. 集成测 `FunxyzClientIntegrationTest`（env-var gated）
4. README 增量小节
5. `FunxyzAddressLookupExample`
6. CHANGELOG 一行
