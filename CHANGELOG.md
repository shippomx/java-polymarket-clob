# Changelog

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
