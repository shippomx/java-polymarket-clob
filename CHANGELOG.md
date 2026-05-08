# Changelog

## 2.1.0 — 2026-05-08

### Breaking changes

- `example.DepositWalletOnboardAndTradeExample` 重命名为 `example.OnboarderExample`。调用方 `mvn exec:java -Dexec.mainClass=...DepositWalletOnboardAndTradeExample` 的脚本需改为 `...OnboarderExample`。

### Added

- `example.FullOnboardAndTradeExample`：原语级 7 步 onboard + trade 演示，对照 TS `clob-client-v2/examples/account/fullOnboardAndTrade.ts`。直接组合 `GammaClient` / `DepositWalletReads` / `ApprovalPlanner` / `BatchEip712` / `DepositWalletRelayer` / `AuthApi` / `AuthenticatedClobClient`，每步显式打印 `txnID` / `state` / `hash` / 响应 JSON，作为生产排障样板。
- `funxyz/` 包：fun.xyz 法币入金地址客户端
  - `FunxyzClient.getDepositAddresses(eoa, recipient)` 封装 `POST api.fun.xyz/v1/eoa`
  - `DepositAddresses` 返回四链固定映射地址（EVM/Solana/Tron/BTC）
  - `FunxyzConfig` 默认带 Polymarket 前端公开 key，可覆盖
  - `FunxyzAddressLookupExample` 端到端 demo
- 外部签名 API（`com.polymarket.clob.signing.ExternalSigning`）：4 对 `buildUnsignedXxx(...)` + `attachXxxSignature(...)`，覆盖 Order V2 POLY_1271（ERC-7739 嵌套 TypedDataSign）、DepositWallet Batch、ClobAuth（L1 头）和 SIWE（EIP-191）。每个 `Unsigned*` 同时携带 `signingDigest32` 与（适用时）`typedDataJson`，可被 `eth_signTypedData_v4` 在 App 端弹窗显示。后端进程可把 digest 透传给手机端钱包远程签名，私钥永不进 BE。

### Changed

- `Pol1271OrderSigner.sign(...)` 与 `L1HeaderBuilder.build(...)` 现在是新 build/attach 原语之上的薄包装。wire 字节输出与之前完全一致（parity 测试不变）。
- `Pol1271OrderSigner.appDomainSeparator(...)` 与 `Pol1271OrderSigner.innerDigest(...)` 由 package-private 提升为 `public static`，以支持外部签名路径。
- `personalSignDigest(String)` 从 `GammaClient`（package-private）移到 `SiweMessage`（public）。`GammaClient.personalSignDigest` 改为转发。

### Notes

- `GammaClient.loginWithSiwe(...)` **未** 重构 — 它持有网络传输与 Bearer auth-token 构造（`base64(payload + ":::" + sig)`），不属于纯签名原语范畴。仅需 SIWE digest 的消费者应直接调 `UnsignedSiwe.buildUnsigned(...)`（或 `ExternalSigning.buildUnsignedSiwe(...)`）。

## 2.0.0 — 2026-05-07

### Breaking changes

- 钱包模型从 Gnosis Safe 切换到 Polymarket Deposit Wallet（Solady CWIA 最小代理）
- 删除 `gasless/` 整包（`GaslessRelayer / SafeEip712 / SafeSignatures / MultiSend / Calldata` 等）
- `auth/builder/` 包内容移到 `auth/`（`BuilderConfig` / `BuilderHeaderBuilder` 仍服务于 Builder API；Safe-relayer Builder HMAC 已退役）
- 删除 `WalletDerivation`（被 `DepositWalletDerivation` 替代）
- 删除 `WalletContractConfig`（被 `DepositWalletConfig` 替代）
- `SignatureType` 枚举仅保留 `EOA(0)` 与 `POLY_1271(3)`；`POLY_PROXY` / `POLY_GNOSIS_SAFE` 已删除
- `ContractRegistry.walletConfig(...)` → `ContractRegistry.depositWalletConfig(...)`
- 链支持从 Polygon + Amoy 收窄至 Polygon only

### Added

- `chain/` 包：`EvmRpcClient` / `Web3jEvmRpcClient` / `DepositWalletReads` / `PolymarketContracts`
- `gamma/` 包：`GammaClient`（SIWE 登录 + 用户档案）+ `GammaSession` + `GammaAuthException` + `SiweMessage`
- `deposit/` 包：`DepositWalletDerivation` / `DepositWalletRelayer` / `ApprovalPlanner` / `BatchEip712` 等
- `order/Pol1271OrderSigner`：ERC-7739 嵌套 TypedDataSign 签名（POLY_1271）
- `onboard/Onboarder`：薄编排器，一次调用跑完 EOA → 下单全链路
- `example/DepositWalletOnboardAndTradeExample`：替换旧 `EndToEndOnboardingExample`

### Migration guide

参见 `docs/superpowers/specs/2026-05-07-deposit-wallet-onboarding-design.md` §8 表 8.1 / 8.2。
