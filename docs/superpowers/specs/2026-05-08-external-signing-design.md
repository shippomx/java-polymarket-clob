# External Signing for Java CLOB SDK · Design Spec

> **Status**: Draft for review
> **Author**: brainstormed via superpowers · 2026-05-08
> **Upstream context**: PolyHub MVP BE 技术方案 `00-mvp-be-tech-design.md` §2.1.2 / §2.2.0 / §3.2 — BE 进程内只暴露 `buildUnsigned*(...)` 与 `attachSignature(...)` 两条路径，私钥永驻 App。
> **Versioning**: planned MINOR bump `2.0.x → 2.1.0`（公共 API 全为新增；现有 API 实现内部重构、行为不变）。

## 1. Goal

让 `com.polymarket:clob-client` 的 Java SDK 在 BE 进程内能够：

1. 从业务参数构造 4 类 unsigned typed data；
2. 把 unsigned 透传给 App 端（手机钱包），由 App 用用户私钥产出 65 字节 ECDSA 签名；
3. BE 把 65 字节签名灌回 SDK，拼出可直接发给 Polymarket CLOB / relayer-v2 的最终 wire 格式。

**反目标**：SDK 不接收用户私钥（`LocalSigner` 仍是接收私钥的唯一类，作为本地路径的实现，不在 BE 进程内被使用）；SDK 不引入新的网络 / KMS / DB 依赖。

## 2. Scope · 4 类 payload

| # | Payload | 域 / 协议 | 业务场景 |
|---|---|---|---|
| 1 | **Order V2 POLY_1271** | EIP-712 嵌套 TypedDataSign（ERC-7739） | 下单（CTF Exchange v2 主路径，2026-04-28+） |
| 2 | **DepositWallet Batch** | EIP-712 plain (`Batch{wallet, nonce, deadline, calls[]}`) | EnableTrade approve-batch / 平仓 redeem / 出金 withdraw — 三类 calldata 全部经由该 Batch |
| 3 | **ClobAuth** | EIP-712 plain | L1 头部，POST `/auth/derive-api-key` 派生 L2 凭证 |
| 4 | **SIWE** | EIP-191 personal_sign | Gamma 登录（`POST /login`） |

**Out of scope（显式）**：
- Order V1（legacy CTF Exchange v1）外部签名 —— `EIP712OrderSigner.sign(...)` 只保留本地路径；
- Order V2 EOA 直签外部签名 —— `EIP712OrderSigner.signV2(...)` 只保留本地路径（BE 走 POLY_1271）；
- "通用 EIP-712 签名" / `signTypedData(json)` 接口 —— 镜像 BE 红线 #2，4 类具名 builder 之外不开口；
- KMS / CredVault / `polymarket_clob_creds` / DB 落库 / relayer-v2 / `bridge.polymarket.com` 出网 —— 不在 SDK 范围（BE 已自有）；
- nonce 单调性 / 防重放 / RiskGuard —— 调用方 (BE) 责任。

## 3. Architecture

### 3.1 总入口（4 对方法 · 同形态）

```java
package com.polymarket.clob.signing;

public final class ExternalSigning {
    // Order V2 POLY_1271
    public static UnsignedOrderV2Pol1271 buildUnsignedOrderV2Pol1271(OrderV2 order, long chainId, boolean negRisk);
    public static SignedOrderV2          attachOrderV2Pol1271Signature(UnsignedOrderV2Pol1271 unsigned, byte[] innerSig65);

    // DepositWallet Batch
    public static UnsignedBatch          buildUnsignedBatch(long chainId, Address wallet, BigInteger nonce,
                                                            BigInteger deadline, List<Call> calls);
    public static SignedBatch            attachBatchSignature(UnsignedBatch unsigned, byte[] sig65);

    // ClobAuth (L1 headers)
    public static UnsignedClobAuth       buildUnsignedClobAuth(Address eoa, long chainId, long timestamp, BigInteger nonce);
    public static Map<String, String>    attachClobAuthSignature(UnsignedClobAuth unsigned, byte[] sig65);

    // SIWE
    public static UnsignedSiwe           buildUnsignedSiwe(SiweMessage args);
    public static SignedSiwe             attachSiweSignature(UnsignedSiwe unsigned, byte[] sig65);
}
```

`ExternalSigning` 是聚合 facade，转发到原包内的 `UnsignedXxx.buildUnsigned(...) / attach(...)` 静态方法。短路径（直接在原包内调）和长路径（走 facade）等价。

### 3.2 Unsigned record 契约（统一形态）

每个 `Unsigned*` 都是不可变 record，统一带 **`signingDigest32`**（权威输入）+ **`typedDataJson`**（App 可走 `eth_signTypedData_v4`），SIWE 例外（EIP-191 不属于 EIP-712）。

```java
public record UnsignedOrderV2Pol1271(
        OrderV2 order,
        long    chainId,
        boolean negRisk,
        byte[]  signingDigest32,    // = innerDigest（App 端权威签名输入）
        String  typedDataJson,      // ERC-7739 嵌套 TypedDataSign envelope
        byte[]  contentsHash,       // 32B · BE attach 拼 wire 时用
        byte[]  appDomainSep,       // 32B · BE attach 拼 wire 时用
        String  orderTypeString     // ASCII · BE attach 拼 wire 时用
) {}

public record UnsignedBatch(
        long       chainId,
        Address    wallet,
        BigInteger nonce,
        BigInteger deadline,
        List<Call> calls,           // 防御性副本
        byte[]     signingDigest32,
        String     typedDataJson
) {}

public record UnsignedClobAuth(
        Address    address,
        long       chainId,
        long       timestamp,
        BigInteger nonce,
        byte[]     signingDigest32,
        String     typedDataJson
) {}

public record UnsignedSiwe(
        SiweMessage message,
        String      canonicalMessage,    // SIWE 标准串（App 弹窗给用户看）
        byte[]      signingDigest32      // = keccak256("\x19Ethereum Signed Message:\n" + len + msg)
        // 注：无 typedDataJson — SIWE 走 EIP-191，非 EIP-712
) {}

public record SignedSiwe(String canonicalMessage, String signatureHex) {}
```

`SignedOrderV2` / `SignedBatch` 复用现有类型，不新建。

### 3.3 ERC-7739 嵌套 TypedDataSign · JSON 模板

`UnsignedOrderV2Pol1271.typedDataJson` 走标准 EIP-712，primaryType 为 `TypedDataSign`，types 表里同时声明 `TypedDataSign` 与 `Order` 两个 struct：

```json
{
  "types": {
    "EIP712Domain": [
      {"name":"name","type":"string"},
      {"name":"version","type":"string"},
      {"name":"chainId","type":"uint256"},
      {"name":"verifyingContract","type":"address"}
    ],
    "TypedDataSign": [
      {"name":"contents","type":"Order"},
      {"name":"name","type":"string"},
      {"name":"version","type":"string"},
      {"name":"chainId","type":"uint256"},
      {"name":"verifyingContract","type":"address"},
      {"name":"salt","type":"bytes32"}
    ],
    "Order": [
      {"name":"salt","type":"uint256"},
      {"name":"maker","type":"address"},
      {"name":"signer","type":"address"},
      {"name":"tokenId","type":"uint256"},
      {"name":"makerAmount","type":"uint256"},
      {"name":"takerAmount","type":"uint256"},
      {"name":"side","type":"uint8"},
      {"name":"signatureType","type":"uint8"},
      {"name":"timestamp","type":"uint256"},
      {"name":"metadata","type":"bytes32"},
      {"name":"builder","type":"bytes32"}
    ]
  },
  "primaryType": "TypedDataSign",
  "domain": {
    "name": "Polymarket CTF Exchange",
    "version": "2",
    "chainId": 137,
    "verifyingContract": "<exchangeV2 / negRiskExchangeV2 address>"
  },
  "message": {
    "contents": { "salt": "...", "maker": "...", ... },
    "name": "DepositWallet",
    "version": "1",
    "chainId": 137,
    "verifyingContract": "<deposit wallet address (= order.signer)>",
    "salt": "0x0000000000000000000000000000000000000000000000000000000000000000"
  }
}
```

**Round-trip 不变量**（单测断言）：`new StructuredDataEncoder(typedDataJson).hashStructuredData() == unsigned.signingDigest32()`。

如 web3j `StructuredDataEncoder` 在嵌套 struct 上行为存在偏差，将在实现期使用手写 EIP-712 编码补足，断言保留。

### 3.4 Local 路径（薄包装）

`Pol1271OrderSigner.sign(signer, order, chainId, negRisk)` 重构为：

```java
UnsignedOrderV2Pol1271 u = ExternalSigning.buildUnsignedOrderV2Pol1271(order, chainId, negRisk);
return signer.signHash(u.signingDigest32())
        .thenApply(sig -> ExternalSigning.attachOrderV2Pol1271Signature(u, sig));
```

`L1HeaderBuilder.build(signer, chainId, ts, nonce)` / `BatchEip712`+ caller pair / `GammaClient.loginWithSiwe(signer, chainId)` 同形改造。`OrderBuilder.createOrderV2(args, opts)` 因为内部委托给 `Pol1271OrderSigner.sign`，不需要直接修改。`LocalSigner.signHash(digest32)` 接口完全保留。

### 3.5 包布局

```
com.polymarket.clob.signing/
└── ExternalSigning.java                # 新 · 静态聚合 facade

com.polymarket.clob.order/
├── UnsignedOrderV2Pol1271.java         # 新 · record
├── Pol1271OrderSigner.java             # 改 · sign() 内部走 buildUnsigned + signHash + attach
└── EIP712OrderSigner.java              # 不动（V1 / V2-EOA 仅本地路径）

com.polymarket.clob.deposit/
├── UnsignedBatch.java                  # 新 · record
├── BatchEip712.java                    # 加 buildUnsigned / attach 静态方法 + typedDataJson 生成
└── SignedBatch.java                    # 不动（attach 直接产出现有类型）

com.polymarket.clob.auth/
├── UnsignedClobAuth.java               # 新 · record
├── L1HeaderBuilder.java                # 改 · build(signer, …) 退化为薄包装
└── Eip712TypedData.java                # 加 typedDataJsonClobAuth(...) 工具方法

com.polymarket.clob.gamma/
├── UnsignedSiwe.java                   # 新 · record
├── SignedSiwe.java                     # 新 · record
├── SiweMessage.java                    # 加 canonicalMessage() / personalSignDigest32()
└── GammaClient.java                    # 不动（继续接收 Signer，内部走 buildUnsigned + signHash + attach）
```

## 4. Validation & Error Handling

### 4.1 入口校验（在 `attachXxxSignature`）

| 校验 | 失败 |
|---|---|
| `sig.length == 65`（不修正 `v -= 27`） | `ClobSignatureException` |
| `unsigned` 不为 null；record 字段非 null（构造时已查） | `NullPointerException` |
| POLY_1271 attach 额外断 `contentsHash.length == 32 && appDomainSep.length == 32` | `ClobSignatureException` |

`build*` 阶段的失败（`StructuredDataEncoder` 抛、地址 / bytes32 长度非法）继续走现有 `ClobSignatureException`，**不新增 ExceptionType**。

### 4.2 显式不做

- `attachXxxSignature` 不重新计算 digest 防篡改 — 调用方 (BE) 自检；
- `buildUnsigned` 不校验业务约束（nonce 单调、deadline 未过期、wallet ≠ 0x0、calls 非空之类）— 调用方 (BE) 责任；
- 不对 `SiweMessage` 的 expiration / domain 做语义校验。

## 5. Testing Strategy

| 层级 | 内容 |
|---|---|
| Round-trip 等价 | 对每个 `Signer` 实现，断言 `buildUnsigned + signHash + attach` 与现有 `*.sign(signer, …)` byte-level 输出一致（4 类 payload 各一对） |
| Golden vector | 复用 `src/test/java/com/polymarket/clob/parity/` 现有 py-clob-client / rs-clob-client diff，4 类 payload 不退化 |
| POLY_1271 wire 拼装 | 单测断 `signedOrder.signatureHex().length == 2 + 2*(65+32+32+orderTypeStringBytes.length+2)` 且字节序与现 `Pol1271OrderSigner.sign` 一致 |
| TypedDataJson ↔ digest | 4 类 payload 单测：`new StructuredDataEncoder(typedDataJson).hashStructuredData() == signingDigest32` |
| ERC-7739 嵌套 | 单独单测：用一组固定 OrderV2 跑 typedDataJson 模板生成 + StructuredDataEncoder 摘要 → 断 `== unsigned.signingDigest32()`；若 web3j 编码与 SDK 内部计算分歧，以 SDK 内部为权威，typedDataJson 生成器修正到一致 |
| SIWE EIP-191 | 单测断 `canonicalMessage` 与 SIWE spec 串完全一致；`signingDigest32 == keccak256("\x19Ethereum Signed Message:\n" + len + msg)` |
| Real-chain smoke | **不加** — BE 自有冒烟 |

## 6. Compatibility & Versioning

- 公共 API 全为新增。现有 API 行为不变，wire-level golden vector 全部保留。
- `OrderBuilder` / `L1HeaderBuilder` / `Pol1271OrderSigner` / `EIP712OrderSigner` / `GammaClient` / `LocalSigner` / `Signer` 接口签名不动。
- 版本号：`2.0.x` → `2.1.0`，`CHANGELOG.md` 加一段「外部签名能力」。

## 7. Open Questions / Risks

| # | Risk | 缓解 |
|---|---|---|
| R1 | web3j `StructuredDataEncoder` 对嵌套 TypedDataSign 的支持不充分 / 与 SDK 内部 hash 不一致 | round-trip 单测会暴露；分歧时手写 EIP-712 编码生成 typedData JSON，权威 digest 仍由 `Pol1271OrderSigner.innerDigest` 出 |
| R2 | App 钱包不支持 ERC-7739 显示 → 用户看到 gibberish | App 端 UX 不在 SDK 范围；SDK 仍提供 raw 32B digest 作为 fallback |
| R3 | ClobAuth `nonce / timestamp` 由谁提供 — BE 还是 SDK | 沿用现有 `L1HeaderBuilder` 签名：`buildUnsignedClobAuth(eoa, chainId, ts, nonce)` 显式接 4 个参数，调用方 (BE) 提供 |
| R4 | `Call.data` 字段在 typedDataJson 里如何序列化（EIP-712 `bytes` 类型 = `keccak256(data)`） | 模板里写 `0x` + hex；StructuredDataEncoder 内部按 `bytes` 类型走 `keccak256(decodeHex(value))` |

## 8. Non-goals · Recap

明示排除：
- Order V1 / Order V2 EOA / 通用 EIP-712 签名接口；
- KMS / CredVault / DB / relayer-v2 / bridge 出网；
- nonce 单调性 / 防重放 / RiskGuard / OFAC guard；
- 真链冒烟测试；
- 新增 ExceptionType；
- App 端 UX。

## 9. Plan Handoff

实现拆分（详细任务清单见后续 plan）：
1. POLY_1271 `UnsignedOrderV2Pol1271` + 嵌套 typedDataJson 模板 + round-trip 单测；
2. DepositWallet `UnsignedBatch` + typedDataJson + round-trip 单测；
3. ClobAuth `UnsignedClobAuth` + typedDataJson + round-trip 单测；
4. SIWE `UnsignedSiwe` + EIP-191 摘要 + 单测；
5. `ExternalSigning` facade + 4 类 attach 方法 + 入口校验 + 单测；
6. 现有 `Pol1271OrderSigner.sign` / `L1HeaderBuilder.build` / `GammaClient.loginWithSiwe` 重构为薄包装；
7. `CHANGELOG.md` + `README.md` 「外部签名」章节；
8. 版本号 bump `2.0.x → 2.1.0`。
