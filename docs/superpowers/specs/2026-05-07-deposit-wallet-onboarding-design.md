# Java SDK 切换到 Polymarket Deposit Wallet 流（v2）

> **状态**：设计稿，待实现
> **作者**：taka@noahbase.com
> **日期**：2026-05-07
> **参考**：`/Users/bmtaka/Downloads/clob-client-v2/docs/EOA_TO_ORDER.md`、`/Users/bmtaka/Downloads/clob-client-v2/examples/account/fullOnboardAndTrade.ts`、`/Users/bmtaka/Downloads/clob-client-v2/src/order-utils/exchangeOrderBuilderV2.ts`

---

## 1. 目标与范围

### 1.1 目标

将 `java-polymarket-clob` SDK 从 Gnosis Safe 钱包流（V1 + V2 增量）整体切换到 Polymarket 在 mid-2026 上线的 **Deposit Wallet 流**（Solady CWIA 最小代理），交付端到端可用的 onboard + trade 管道，与官方 TypeScript 例子 `fullOnboardAndTrade.ts` 行为一致。

### 1.2 范围

**包含：**

- 新增 EVM RPC 客户端（基于已有 web3j 依赖），支持 6 类链上读取
- 新增 Gamma SIWE 登录 + 用户档案（profile）创建 + relayer cookie 鉴权
- 新增 Deposit Wallet 派生（on-chain `predictWalletAddress`）+ 部署提交（`WALLET-CREATE`）
- 新增 EIP-712 `Batch` 类型签名 + 批量授权动态规划（13 项）
- 新增 ERC-7739 嵌套 `TypedDataSign` 订单签名（POLY_1271）
- 新增高层 `Onboarder` 编排器 + 6 项构件块
- 删除：`gasless/` 包、`auth/builder/` 包、Safe / Proxy 派生路径、Safe 相关示例与测试

**不包含：**

- Amoy / Mumbai / 其他测试网支持
- 远程签名器（HSM / KMS）的 EIP-712 嵌套签名适配
- WebSocket 订阅（`ws/` 包不动）
- Builder fee 折扣计算（`metadata` / `builder` 字段保留，默认 `0x00..00`）

### 1.3 关键决策摘要

| # | 决策 | 选择 |
|---|---|---|
| Q1 | 与现有 Safe 流共存还是替换 | **完全替换** |
| Q2 | 链上读取的实现方式 | 新增薄 `EvmRpcClient`（web3j） |
| Q3 | Gamma 登录覆盖范围 | `/nonce` + `/login` + `/users` + `/profiles` 全覆盖 |
| Q4 | 公共 API 形态 | 构件块 + 薄编排器 |
| Q5 | 是否含 POLY_1271 订单签名 | 含（端到端到下单完成）|

---

## 2. 现状分析

### 2.1 当前 SDK 状态

- 走 Gnosis Safe 流：`WalletDerivation.deriveSafeWallet`、`GaslessRelayer` 走 `SAFE-CREATE` / `SAFE` wire、MultiSend SafeTx 打包、`personal_sign + v += 4`
- Relayer 鉴权用 Builder HMAC（`auth/builder/BuilderHeaderBuilder`）
- `SignatureType` 已有 `POLY_1271 = 3` 枚举值，但 `OrderBuilder.assembleOrderV2` 第 181 行显式 `throw UnsupportedOperationException` 拒绝该路径
- `OrderV2` / `LimitOrderArgsV2` / `MarketOrderArgsV2` / `SignedOrderV2` 数据模型已就绪
- `Eip712TypedData.hashOrderV2` 可计算 V2 订单 struct hash，但 `EIP712OrderSigner.signV2` 只产 65B 平 ECDSA 签名 hex，**无法**直接用于 EIP-1271 钱包验签
- `chain/` / `gamma/` / `deposit/` / `onboard/` 包目前不存在

### 2.2 EOA_TO_ORDER.md 描述的目标流

EOA 私钥 → Gamma SIWE 登录 → 派生 Deposit Wallet → 部署（首次）→ 批量 approve → 派生 CLOB L2 凭证 → 下单。链上零自付 gas，全程仅一份 EOA 私钥 + RPC + 三个外部服务（Gamma / Relayer V2 / CLOB）。

### 2.3 关键不变量（来自 HAR 反向工程 + TS 源码）

1. `proxyWallet = predictWalletAddress(IMPL, pad32(eoa))` 必须在 `POST /profiles` 之前算好
2. EOA 地址用于 L1 / L2 鉴权头与 SIWE 签名；wallet 地址用于 order `maker / signer`，二者**不可混用**
3. `POLY_1271 (3)` + `maker == signer == wallet` 是 Deposit Wallet 流唯一组合
4. WALLET batch `deadline ≥ now + 1800s`
5. 一次 onboarding 不固定批次数；脚本须 read-then-plan，不硬编码
6. L2 HMAC `requestPath` 不含 query，body 字符串原样不可重新序列化
7. Order signature 是嵌套 ERC-7739 包，~200B；手写不出来，必须按 TS 源字节复刻

---

## 3. 包结构与组件

### 3.1 删除清单

| 目录 / 类 | 备注 |
|---|---|
| `gasless/` 整包 | `GaslessRelayer / SafeEip712 / SafeSignatures / SafeTxPayload / SafeTxStyle / MultiSend / Calldata / Words / RelayerTx / RelayerTxResult / package-info` |
| `auth/builder/` 整包 | Builder HMAC 仅用于旧 relayer，新流走 gamma cookie |
| `auth/WalletDerivation` 中 Safe / Proxy 路径 | 文件保留为 `DepositWalletDerivation`，仅留 deposit wallet 派生 |
| `SignatureType.POLY_PROXY / POLY_GNOSIS_SAFE` | 枚举值删除 |
| `model.WalletContractConfig` | 替换为 `chain.DepositWalletConfig` |
| `example.SafeWalletExample` | 替换为新例子 |
| `example.PolymarketBridge` | 删除 |
| `example.EndToEndOnboardingExample` | 替换为新例子 |
| `src/test/java/.../gasless/*Test.java` | 4 个测试文件 |
| `src/test/java/.../auth/builder/BuilderHeaderBuilderTest.java` | 删除 |
| 测试 fixture `fixtures/parity/safe/*`、`fixtures/parity/builder-relayer/*` | 删除 |

### 3.2 新增包与文件

```
src/main/java/com/polymarket/clob/
├── chain/
│   ├── EvmRpcClient.java              (interface)
│   ├── Web3jEvmRpcClient.java         (default impl, 用 web3j)
│   ├── DepositWalletReads.java        (6 类读取的 ABI 编解码)
│   └── PolymarketContracts.java       (常量地址表 + EIP-712 type strings)
├── gamma/
│   ├── GammaClient.java               (login + users + profiles)
│   ├── SiweMessage.java               (EIP-4361 文本拼接)
│   └── GammaSession.java              (cookie + 过期时间)
├── deposit/
│   ├── DepositWalletDerivation.java   (重命名自 WalletDerivation)
│   ├── DepositWalletRelayer.java      (替换 GaslessRelayer)
│   ├── ApprovalPlanner.java           (read-then-plan)
│   ├── ApprovalTargets.java           (13 项标准目标列表)
│   ├── BatchEip712.java               (DepositWallet 域 Batch / Call 类型)
│   ├── DepositWalletConfig.java       (替换 WalletContractConfig)
│   ├── RelayerTx.java                 (从 gasless/ 移过来精简)
│   └── RelayerTxResult.java
├── order/
│   └── Pol1271OrderSigner.java        (新增 ERC-7739 嵌套签名)
├── onboard/
│   ├── Onboarder.java                 (薄编排器)
│   ├── OnboardingConfig.java
│   ├── OnboardingResult.java
│   └── TestOrderArgs.java
└── example/
    └── DepositWalletOnboardAndTradeExample.java
```

### 3.3 改动但保留

| 文件 | 改动 |
|---|---|
| `OrderBuilder` | 删掉对 `POLY_1271` 抛异常的分支，路由到 `Pol1271OrderSigner` |
| `EIP712OrderSigner.signV2` | 保留，仅用于 EOA / 非 1271 路径（测试可继续覆盖）|
| `SignatureType` | 仅留 `EOA(0)` 与 `POLY_1271(3)` |
| `model.ContractRegistry` | 替换 Safe 字段为 Deposit Wallet 配置 |
| `ClobClientBuilder` / `AuthenticatedClobClient` | 编排器构建出的客户端默认 `signatureType = POLY_1271` |

### 3.4 组件契约（关键方法签名）

#### `chain/EvmRpcClient`

```java
public interface EvmRpcClient {
    CompletableFuture<byte[]> ethCall(Address to, byte[] callData);
    CompletableFuture<byte[]> getCode(Address addr);
}
```

#### `chain/DepositWalletReads`

```java
public final class DepositWalletReads {
    DepositWalletReads(EvmRpcClient rpc, long chainId);
    CompletableFuture<Address>     predictWalletAddress(Address eoa);
    CompletableFuture<Boolean>     isDeployed(Address wallet);
    CompletableFuture<BigInteger>  walletNonce(Address wallet);
    CompletableFuture<BigInteger>  erc20Allowance(Address token, Address owner, Address spender);
    CompletableFuture<Boolean>     ctfApprovedForAll(Address owner, Address operator);
    CompletableFuture<BigInteger>  erc20BalanceOf(Address token, Address owner);
}
```

#### `gamma/GammaClient`

```java
public final class GammaClient {
    GammaClient(URI baseUrl, HttpClient http);
    CompletableFuture<GammaSession> loginWithSiwe(Signer eoa, long chainId);
    CompletableFuture<Boolean>      profileExists(GammaSession s, Address eoa);
    CompletableFuture<Void>         createProfile(GammaSession s, Address eoa, Address proxyWallet);
    CompletableFuture<Void>         ensureProfile(GammaSession s, Address eoa, Address proxyWallet);
}

public record GammaSession(String cookieHeader, Instant expiresAt) {}
```

#### `deposit/DepositWalletRelayer`

```java
public final class DepositWalletRelayer {
    DepositWalletRelayer(URI baseUrl, HttpClient http, GammaSession session);
    String submitWalletCreate(Address eoa, Address factory) throws IOException;
    String submitBatch(SignedBatch batch) throws IOException;
    BigInteger getRelayerNonce(Address eoa) throws IOException;
    RelayerTxResult getTransaction(String txId) throws IOException;
    RelayerTxResult waitForTx(String txId, Duration poll, int maxAttempts) throws IOException;
}

public record Call(Address target, BigInteger value, byte[] data) {}
public record SignedBatch(
    Address eoa, Address factory, Address wallet,
    BigInteger nonce, BigInteger deadline,
    List<Call> calls, byte[] signature65
) {}
```

#### `order/Pol1271OrderSigner`

```java
public final class Pol1271OrderSigner {
    static CompletableFuture<SignedOrderV2> sign(
        Signer eoa, OrderV2 order, long chainId, boolean negRisk);
}
```

#### `onboard/Onboarder`

```java
public final class Onboarder {
    Onboarder(OnboardingConfig cfg);
    CompletableFuture<OnboardingResult> run(Signer eoa);
}

public record OnboardingResult(
    Address wallet,
    ApiCredentials creds,
    GammaSession session,
    Optional<PostOrderResponse> testOrder
) {}
```

### 3.5 设计取舍

- `EvmRpcClient` 接口故意只有 `ethCall` + `getCode` 两个方法。具体的 6 类读取在 `DepositWalletReads` 里做 ABI 编解码 —— 让 RPC 客户端层和业务读取层解耦，便于用户接入自有 RPC（Alchemy / Infura / 内部池）
- `GammaSession` 是值类型；`GammaClient` 无状态。Session 7 天有效，编排器秒级跑完，不做续签机制
- `Pol1271OrderSigner` 单独成类不并入 `EIP712OrderSigner`：嵌套包装非平凡，需要独立测试与字节级 fixture

---

## 4. 端到端数据流

```
EOA private key
  │
  ▼
[Onboarder.run]
  │
  │ 1. GammaClient.loginWithSiwe(eoa, 137)
  │      ├── GET  gamma/nonce              → cookie₀ + nonce
  │      ├── EOA.signMessage(SIWE text)
  │      └── GET  gamma/login (Bearer)     → cookie_auth (合并 cookie₀)
  │      ⇒ GammaSession
  │
  │ 2. DepositWalletReads.predictWalletAddress(eoa)
  │      └── eth_call factory.predictWalletAddress(IMPL, pad32(eoa))
  │      ⇒ wallet
  │
  │ 3. GammaClient.ensureProfile(session, eoa, wallet)
  │      ├── GET  gamma/users?address=<eoa>     → 200 ⇒ skip / 404 ⇒ create
  │      └── POST gamma/profiles { proxyWallet=wallet, users=[...] }
  │
  │ 4. DepositWalletReads.isDeployed(wallet)
  │      └── eth_getCode(wallet) == "0x" ?
  │      ↓ if not deployed
  │      DepositWalletRelayer.submitWalletCreate(eoa, FACTORY)
  │      └── POST relayer-v2/submit { type:"WALLET-CREATE", from, to:FACTORY }
  │      └── poll relayer-v2/transaction → STATE_CONFIRMED
  │
  │ 5. ApprovalPlanner.planMissingApprovals(wallet)
  │      ├── 13 项 (token, kind, spender) 逐项读 allowance / isApprovedForAll
  │      └── 仅缺失项进入 calls
  │      ↓ if calls.size() > 0
  │      a. nonce = DepositWalletReads.walletNonce(wallet)   (链上读，权威)
  │      b. deadline = now + 1800
  │      c. digest = BatchEip712.hashBatch(chainId, wallet, nonce, deadline, calls)
  │      d. sig65 = eoa.signHash(digest)
  │      e. DepositWalletRelayer.submitBatch(SignedBatch{...})
  │      f. waitForTx → STATE_CONFIRMED
  │
  │ 6. AuthApi.deriveOrCreateApiKey(eoa)        (复用现有 L1 EIP-712)
  │      ├── GET  clob/auth/derive-api-key     → 200 ⇒ existing creds
  │      └── POST clob/auth/api-key (on 400)   → new creds
  │      ⇒ ApiCredentials{key, secret, passphrase}
  │
  │ 7. (可选) Pol1271OrderSigner.sign + OrderApi.postOrder
  │      ├── OrderV2{maker = signer = wallet, sigType = POLY_1271, ...}
  │      ├── inner = signTypedData(CTFExchangeV2 域 + TypedDataSign 嵌套结构)
  │      ├── final = inner65 || appDomainSep32 || contentsHash32 || ORDER_TYPE_STRING || lenBE2
  │      └── POST clob/order with L2 HMAC
  │
  ▼
OnboardingResult{wallet, creds, session, optional testOrder response}
```

### 4.1 失败模型 —— 编排器内不重试

| 失败 | 异常 |
|---|---|
| 网络 / 非 2xx | `IOException` |
| Relayer `STATE_FAILED` / `STATE_INVALID` | `RelayerTxFailedException`（含 txId、链上 hash、reason） |
| Gamma 401 / 403 | `GammaAuthException`（多半是 SIWE 文本错或时钟漂移） |
| 钱包派生与 RPC 不一致 | log 警告，信任 RPC 返回值 |
| 批量 deadline 过期 | relayer 拒绝；原样抛错，由用户重跑 |

轮询：2s 间隔，90 次，~3 min 上限（与旧 `GaslessRelayer` 行为一致）。

### 4.2 幂等性

每一步先读状态：

- 钱包已部署 → 跳过步骤 4
- 全部 allowance 已 max → calls 为空，跳过步骤 5
- profile 已存在 → 跳过 POST `/profiles`
- derive-api-key 200 → 复用 creds

`Onboarder.run` 重入安全。

---

## 5. 关键签名细节

### 5.1 `BatchEip712.hashBatch` —— Deposit Wallet 域 Batch 摘要

```
domain = EIP712Domain{
    name:              "DepositWallet",
    version:           "1",
    chainId:           137,
    verifyingContract: <wallet>            // 注意是 wallet，不是 factory
}

types = {
    EIP712Domain: [name string, version string, chainId uint256, verifyingContract address],
    Batch: [
        wallet   address,
        nonce    uint256,
        deadline uint256,
        calls    Call[]
    ],
    Call: [
        target address,
        value  uint256,
        data   bytes        // 712 中编码为 keccak256(data)
    ]
}

primaryType = "Batch"
message     = { wallet, nonce, deadline, calls: [{target, value, data}, ...] }
```

要点：

- `Call.data` 为 `bytes` 类型，712 编码为 `keccak256(data)` 当 32 字节用
- `Call[]` 数组哈希 = `keccak256(concat(callHash_i for i))`
- `digest = keccak256(0x1901 || domainSeparator || keccak256(structHash))`
- `domainSeparator` 中 `verifyingContract = wallet`（每用户一份，不可全局缓存）

`/submit` body 字段顺序敏感（与 TS 一致）：

```json
{
  "type": "WALLET",
  "from": "<eoa lower hex>",
  "to":   "<factory lower hex>",
  "nonce": "<nonce decimal string>",
  "signature": "0x<65B hex>",
  "depositWalletParams": {
    "depositWallet": "<wallet lower hex>",
    "deadline":      "<deadline decimal string>",
    "calls": [
      { "target": "<lower hex>", "value": "<decimal string>", "data": "0x<hex>" }
    ]
  }
}
```

`WALLET-CREATE` 提交体仅 `{type, from, to}` 三字段，无 `signature` / `nonce` / `depositWalletParams`，由 EOA SIWE cookie 鉴权。

### 5.2 `Pol1271OrderSigner.sign` —— ERC-7739 嵌套 TypedDataSign

字节级布局严格复刻 `clob-client-v2/src/order-utils/exchangeOrderBuilderV2.ts:147-221`，**不**按 `EOA_TO_ORDER.md` §7.4 反向工程描述实现（那里分段切错）。

#### 5.2.1 计算 contentsHash

```
contentsHash = keccak256(
    abi.encode(
        ORDER_TYPE_HASH,                                // bytes32 (常量 = keccak256(ORDER_TYPE_STRING))
        order.salt,                                     // uint256
        order.maker,                                    // address  (= wallet)
        order.signer,                                   // address  (= wallet)
        order.tokenId,                                  // uint256
        order.makerAmount,                              // uint256
        order.takerAmount,                              // uint256
        uint8(order.side),                              // uint8
        uint8(order.signatureType),                     // uint8 (= 3)
        order.timestamp,                                // uint256 (毫秒)
        bytes32(order.metadata),                        // bytes32
        bytes32(order.builder)                          // bytes32
    )
)
```

#### 5.2.2 内层 TypedDataSign 签名

```
innerDomain = {
    name:              CTF_EXCHANGE_V2_DOMAIN_NAME,    // 常量
    version:           CTF_EXCHANGE_V2_DOMAIN_VERSION, // 常量
    chainId:           137,
    verifyingContract: negRisk ? NEG_RISK_EXCHANGE_V2 : EXCHANGE_V2
}

types = {
    TypedDataSign: TYPED_DATA_SIGN_STRUCT,             // 常量
    Order:         CTF_EXCHANGE_V2_ORDER_STRUCT        // 常量
}

primaryType = "TypedDataSign"
value = {
    contents:          <原 OrderV2 message 整体>,
    name:              "DepositWallet",                // 钱包域
    version:           "1",
    chainId:           137,
    verifyingContract: order.signer,                   // = wallet
    salt:              0x00..00 (bytes32)
}
```

EOA `signTypedData(domain, types, value)` → `innerSig` (65B)。

#### 5.2.3 拼最终 signature

```
signature = innerSig (65B)
         || appDomainSep (32B)
         || contentsHash (32B)
         || ORDER_TYPE_STRING_ascii (N B)
         || uint16_BE(N) (2B)
```

`appDomainSep` 由内层 domain 计算并按 (chainId, negRisk) 缓存为静态常量表。`ORDER_TYPE_STRING` 字面 ASCII 长度约 186 字节，对应 `lenBE = 0x00BA`。

### 5.3 常量来源

新增 `chain/PolymarketContracts.java` 集中所有：

- 链上地址（factory / impl / 13 项 token-spender）
- EIP-712 type strings（`ORDER_TYPE_STRING`、`TYPED_DATA_SIGN_STRUCT`、`CTF_EXCHANGE_V2_ORDER_STRUCT`）
- 域名 / 版本字符串（`CTF_EXCHANGE_V2_DOMAIN_NAME` 等）

每个常量加 `// SOURCE:` 注释，指向 TS 文件的具体行。

### 5.4 风险与对策

| # | 风险 | 对策 |
|---|---|---|
| 1 | type string 1 字节差 → 链上 1271 验签失败 | 单测把字节序列与预期 keccak 比对 |
| 2 | `appDomainSep` 错合约（普通 vs negRisk）| `negRisk` 入参分流，两条路径各一条 fixture |
| 3 | `bytes` 在 Batch 712 漏 keccak | 用 viem 计算的 fixture 字节级比对 |
| 4 | timestamp 单位（秒 vs 毫秒）混淆 | 强制毫秒，构造时 `System.currentTimeMillis()` 并文档注明 |

---

## 6. 配置与链注册

### 6.1 `DepositWalletConfig`

```java
public record DepositWalletConfig(
    Address factory,            // 0x00000000000Fb5C9ADea0298D729A0CB3823Cc07
    Address implementation,     // 0x58CA52ebe0DadfdF531Cde7062e76746de4Db1eB
    Address usdcE,              // 0xC011a7E12a19f7B1f670d46F03B03f3342E82DFB
    Address usdcNative,         // 0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174
    Address ctf,                // 0x4D97DCd97eC945f40cF65F87097ACe5EA0476045
    Address exchangeV2,         // 0xE111180000d2663C0091e4f400237545B87B996B
    Address negRiskExchangeV2,  // 0xe2222d279d744050d28e00520010520000310F59
    Address negRiskAdapter,     // 0xd91E80cF2E7be2e162c6513ceD06f1dD0dA35296
    Address pUsdQuoter,         // 0x93070a847efef7f70739046a929d47a521f5b8ee
    Address newSpenderA,        // 0xada100db00ca00073811820692005400218fce1f
    Address newSpenderB,        // 0xada2005600dec949baf300f4c6120000bdb6eaab
    Address parlay              // 0xf3cfb6a6ebfeb51876289eb235719eb1c65252b0
) {}
```

`ContractRegistry` 暴露：

```java
public static Optional<DepositWalletConfig> depositWalletConfig(long chainId);
public static Optional<Address> exchangeV2(long chainId, boolean negRisk);  // 保留
```

链支持：

| chainId | 名称 | 支持 |
|---|---|---|
| 137 | Polygon | ✅ |
| 80002 | Amoy | ❌（DepositWalletFactory 未部署，返回 `Optional.empty()`，编排器构造期抛 `IllegalStateException`） |

### 6.2 `ApprovalTargets` —— 13 项标准目标（顺序锁死）

```
1.  USDC.e   ERC20 → CTF
2.  USDC.e   ERC20 → ExchangeV2
3.  USDC.e   ERC20 → NegRiskExchangeV2
4.  USDC.e   ERC20 → NegRiskAdapter
5.  USDC.e   ERC20 → newSpenderA
6.  USDC.e   ERC20 → newSpenderB
7.  USDC.n   ERC20 → pUsdQuoter
8.  CTF      CTF   → ExchangeV2
9.  CTF      CTF   → NegRiskExchangeV2
10. CTF      CTF   → NegRiskAdapter
11. CTF      CTF   → newSpenderA
12. CTF      CTF   → newSpenderB
13. CTF      CTF   → parlay
```

> HAR 显示前端拆为 12 + 1 两批，新 SDK 跟 `fullOnboardAndTrade.ts` 用一批 13 笔。relayer 实测两种都接受；单批省一次签名。

### 6.3 `OnboardingConfig`

```java
public record OnboardingConfig(
    long chainId,                          // 默认 137
    URI gammaHost,                         // 默认 https://gamma-api.polymarket.com
    URI relayerHost,                       // 默认 https://relayer-v2.polymarket.com
    URI clobHost,                          // 默认 https://clob.polymarket.com
    URI rpcUrl,                            // 必填
    Duration relayerPollInterval,          // 默认 2s
    int relayerMaxAttempts,                // 默认 90
    HttpClient httpClient,                 // 可选，null = 内建
    SaltSource saltSource,                 // 可选，null = SecureRandom
    Optional<TestOrderArgs> testOrder      // 缺省 = 跳过下单
) {
    public static Builder builder();
}

public record TestOrderArgs(
    BigInteger tokenId,
    Side side,
    BigDecimal price,
    BigDecimal size,
    OrderType orderType,                   // FAK / GTC / GTD / FOK
    String tickSize,                       // "0.01" / "0.001"
    boolean negRisk
) {}
```

### 6.4 例子文件配置约定

`DepositWalletOnboardAndTradeExample.java` 使用环境变量与 TS 例子对齐：

```
PK         必填  EOA 私钥 0x...
RPC_URL    可选  默认 https://polygon-rpc.com
TOKEN_ID   可选  传值则跑下单步骤；缺省仅 onboarding
```

---

## 7. 测试策略

### 7.1 测试分层

| 层 | 目的 | 是否打外网 | 是否进 CI |
|---|---|---|---|
| 单元测试 | 纯函数 / 序列化 / 签名格式 | ❌ | ✅ |
| Parity 测试 | wire 字节级与 TS 参考一致 | ❌（fixture 比对） | ✅ |
| Capture 集成测试 | HTTP / RPC 用 captor mock | ❌ | ✅ |
| 真链冒烟（手工） | 端到端真签真发 | ✅ | ❌ |

### 7.2 单元测试清单

**`chain/`**

- `Web3jEvmRpcClientTest`：WireMock 模拟 JSON-RPC 端点，覆盖 `eth_call` / `eth_getCode` 编解码、错误响应、空 bytecode (`"0x"`)
- `DepositWalletReadsTest`：固定 `EvmRpcClient` 返回值，验证：
  - `predictWalletAddress` calldata 字节序列（selector + impl + pad32(eoa)）
  - `walletNonce` 32B 返回解码为 BigInteger
  - `erc20Allowance` / `ctfApprovedForAll` selector & 入参编码

**`gamma/`**

- `SiweMessageTest`：固定 (eoa, chainId, nonce, issuedAt) → 文本逐字节比对
- `GammaClientTest`：MockHttpServer 验证：
  - `/login` 头 `Authorization: Bearer <b64(payload):::<sig>>` 格式
  - cookie 合并顺序（nonce cookie + auth cookie）
  - `/users` 200 / 404 → `profileExists` 分支
  - `/profiles` body 字段顺序

**`deposit/`**

- `BatchEip712Test`：固定输入 → 比对预期 32B digest（fixture 来自 viem 同输入）
- `ApprovalPlannerTest`：mock `DepositWalletReads` 13 项混合（已 max / 0 / 已授权 / 未授权）→ 校验输出
- `DepositWalletRelayerTest`：captor 验证 wire body 字段顺序、`/submit` POST、`/transaction` 轮询、`STATE_FAILED` 异常路径

**`order/`**

- `Pol1271OrderSignerTest`：核心，三层验证：
  1. `contentsHash` 比对 viem 同输入结果
  2. `appDomainSep`（EXCHANGE_V2 + NEG_RISK_EXCHANGE_V2）各一条 fixture
  3. 完整 `signature` 字节布局：`innerSig(65) || appDomainSep(32) || contentsHash(32) || ORDER_TYPE_STRING || lenBE(2)`，`lenBE` = `0x00BA`
  - 用固定私钥重放 EOA 内层 712 ECDSA，要求最终签名前缀逐字节等于 fixture

**`onboard/`**

- `OnboarderTest`：captor 替换 4 个外部依赖（gamma / relayer / rpc / clob auth），验证幂等：
  - case A：全新 EOA 跑完 7 步
  - case B：钱包已部署 + 全部 allowance 已 max → 步骤 4、5 跳过
  - case C：批量授权部分缺失 → 计划出正确子集

### 7.3 Parity 测试

保留 `src/test/java/com/polymarket/clob/parity/` 框架（`ParityFixtureDispatcher` + `HeaderNormalizer` + `ParityRequestSnapshot`）。新增 fixture：

```
src/test/resources/fixtures/parity/v2/deposit-wallet/
├── batch-eip712-12-calls.json
├── batch-eip712-1-call-parlay.json
├── pol1271-order-buy-fak.json
├── pol1271-order-sell-gtc.json
├── siwe-message.json
├── relayer-submit-wallet-create.json
└── relayer-submit-wallet-batch.json
```

Fixture 来源优先级：

1. 跑 TS 例子人工 dump 同输入下的输出
2. 回退：HAR 解码出的字节序列（`docs/full.har`）
3. 禁止手工填值

旧 Safe parity fixture 整体删除。

### 7.4 真链冒烟

`DepositWalletOnboardAndTradeExample` 直接跑 main：

- `PK` env 提供私钥
- `TOKEN_ID` 缺省时仅跑 onboarding
- 资金来源：用户自备 ≥ 1 USDC.e 转入 deposit wallet
- 不进 CI；README 增加「真链冒烟流程」段落写预置条件

### 7.5 测试代码删除

随 Safe 删除：

- `src/test/java/com/polymarket/clob/gasless/*Test.java`（4 个）
- `src/test/java/com/polymarket/clob/auth/builder/BuilderHeaderBuilderTest.java`
- `src/test/java/com/polymarket/clob/auth/WalletDerivationTest.java` 中 Safe / Proxy 用例
- `fixtures/parity/safe/`、`fixtures/parity/builder-relayer/`

### 7.6 工具链

- JUnit 5（已用）
- AssertJ（已用）
- **WireMock 3.x（新增 test scope dep）**：现有 captor 是手写最小实现，覆盖不到 SSE / cookie 复杂场景；WireMock 让 gamma + relayer 双 host 测试更省事

---

## 8. 迁移与破坏性变更

### 8.1 公共 API 删除（编译失败）

| 删除符号 | 替换 |
|---|---|
| `gasless.GaslessRelayer` | `deposit.DepositWalletRelayer` |
| `gasless.SafeEip712 / SafeSignatures / SafeTxPayload / SafeTxStyle` | 无（Safe 流不再支持） |
| `gasless.MultiSend / Calldata / Words` | `deposit.BatchEip712` + `ApprovalTargets` |
| `gasless.RelayerTx / RelayerTxResult` | 同名移到 `deposit/`，去掉 Safe 专属字段 |
| `auth.builder.BuilderConfig / BuilderHeaderBuilder` | 删 |
| `auth.WalletDerivation.deriveProxyWallet / deriveSafeWallet / deriveFunder` | `deposit.DepositWalletDerivation.predictWalletAddress`（签名从 `Optional<Address>` 改为 `CompletableFuture<Address>`） |
| `auth.SignatureType.POLY_PROXY / POLY_GNOSIS_SAFE` | 仅留 `EOA` 和 `POLY_1271` |
| `model.WalletContractConfig` | `chain.DepositWalletConfig` |
| `model.ContractRegistry.walletConfig(...)` | `ContractRegistry.depositWalletConfig(...)` |
| `example.SafeWalletExample / PolymarketBridge / EndToEndOnboardingExample` | `example.DepositWalletOnboardAndTradeExample` |
| `OrderBuilder` 中 `POLY_1271` 抛 `UnsupportedOperationException` 分支 | 路由到 `Pol1271OrderSigner` |

### 8.2 行为破坏

| 行为 | 旧 | 新 |
|---|---|---|
| 钱包派生 | 本地 CREATE2，Polygon + Amoy | 必须有 RPC，Polygon only |
| 部署提交 | `SAFE-CREATE` wire | `WALLET-CREATE` wire |
| 批量提交 | `SAFE` wire + Builder HMAC | `WALLET` wire + gamma cookie |
| 批量项数 | 6（V1）/ 10（V2） | 13（动态规划，已 max 不出现） |
| 订单 maker / signer | EOA 或 Safe 地址 | 必须 wallet（DepositWallet 地址） |
| 订单 signatureType | `1` / `2` | 强制 `3`（POLY_1271） |
| 订单签名格式 | 65B ECDSA hex | 嵌套 ERC-7739 包，~200B |
| 链支持 | Polygon + Amoy | Polygon only |

### 8.3 配置变更

`pom.xml`：

```diff
+ <dependency>
+   <groupId>com.github.tomakehurst</groupId>
+   <artifactId>wiremock-jre8-standalone</artifactId>
+   <version>3.x</version>
+   <scope>test</scope>
+ </dependency>
```

`web3j-core` 已是 dep，新代码继续用。

### 8.4 版本

- `pom.xml` `<version>` 跳大版本号（如 `1.x → 2.0.0`）
- 新增 `CHANGELOG.md` 记录 v2 破坏性变更
- README 顶部 banner：`> ⚠️ v2 已切换到 Deposit Wallet 流，与 v1 Safe 流不兼容`

### 8.5 README 更新

| 章节 | 改动 |
|---|---|
| 顶部 banner | 加 v2 不兼容警告 |
| Quickstart | 新例子代码替换旧 Safe 例子 |
| 链支持 | Polygon only，Amoy 段整段删 |
| 鉴权 | gamma SIWE + relayer cookie + CLOB L1/L2 三层叙述 |
| 真链冒烟 | 新增段落，明确 `PK / RPC_URL / TOKEN_ID` 三个 env 与 1 USDC 入金前置 |
| 迁移指南 | 8.1 + 8.2 表 |

---

## 9. 范围外（未来再做）

- Amoy / Mumbai / 其他测试网支持
- 远程签名器（HSM / KMS）适配 EIP-712 嵌套签名
- WebSocket 订阅（`ws/` 包不动）
- Builder fee 折扣计算（`metadata` / `builder` 字段保留默认零值）
