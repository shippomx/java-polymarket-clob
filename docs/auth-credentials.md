# Polymarket 三套鉴权凭证对照

> 范围：本仓 (`java-polymarket-clob`) 与 Polymarket 链上 / 链下系统打交道时使用的三种凭证。
> 关键结论一句话：**`GammaSession`、EOA 签名、HMAC L2 头分别守着「谁在登录」/「谁授权了这串字节」/「谁能调 CLOB 内部接口」三个独立的信任面**，互不可替代。

---

## 1. 三套凭证速览

| 维度 | GammaSession (cookie) | EOA 签名 (EIP-712 / EIP-191 / EIP-1271) | HMAC L2 头 (API key 三件套) |
|---|---|---|---|
| **本质** | 浏览器登录态：合并的 `Cookie` 头 + 过期时间 | 用 EOA 私钥对结构化或文本数据做 ECDSA 签名 | `HMAC-SHA256(secret, ts+method+path+body)` |
| **签发方** | Gamma `/login`（消耗 SIWE 文本） | EOA 自签（`LocalSigner` / 硬件钱包） | CLOB `/auth/api-key`（用 EOA 派生） |
| **作用域** | Polymarket 自家后台：Gamma（用户档案）+ Relayer V2（链上代付） | 链上合约 + 链下 Polymarket 业务签名：Exchange 撮合验证、CTF 调用、SIWE、batch authorize 等 | CLOB 撮合 REST API：下单 / 撤单 / 个人订单 / 评分 / 余额读 |
| **凭证形态** | `record(cookieHeader, expiresAt)`，单字段就是 `name1=v1; name2=v2` | 65 字节 `r‖s‖v` | `(apiKey, secret, passphrase)` 三元组 |
| **生命周期** | 7 天（SIWE expirationTime） | 单次签名一次性使用，无过期概念 | 长期有效，直到主动 revoke |
| **是否上链** | 否（只是 HTTP cookie） | 取决于场景：链上撮合 / redeem 会上链；SIWE / batch 仅链下 | 否（HMAC 只是 HTTP 头） |
| **可重放** | 是（cookie 在有效期内反复用） | 单次 nonce / digest 内唯一；批量 batch 受 wallet nonce 保护 | 是（带时间戳，服务端自己控偏差窗口） |
| **失效后果** | Gamma 401、Relayer 401 → 重新走 SIWE | 签名无效会被 Exchange revert；订单 / batch 拒收 | CLOB 401 → 重新派生 API key |
| **代码入口** | `GammaClient.loginWithSiwe` → `ensureProfile` / `DepositWalletRelayer.submit*` | `EIP712OrderSigner.sign[V2]` / `Pol1271OrderSigner.sign` / `BatchEip712.hashBatch` / `personalSign(SIWE)` | `L2HeaderBuilder.build(...)`（在 `OrderApiImpl` / `AccountApiImpl` 等里调用） |
| **API key 派生时的角色** | / | 派生本身要 EOA 对一段 `clob-derive-api-key` 文本 EIP-712 签 | 派生**结果**就是这把 HMAC 三件套 |

---

## 2. GammaSession：Polymarket 自家后台的登录态

`record GammaSession(String cookieHeader, Instant expiresAt)`，本质是 SIWE 登录后合并的 `Cookie` 头 + 过期时间戳。

### 2.1 用途

| 场景 | 入口 | 备注 |
|---|---|---|
| SIWE 登录获得 session | `GammaClient.loginWithSiwe` | `GET /nonce` → personal_sign → `GET /login` → 合并 Set-Cookie |
| 用户档案存在性查询 | `GammaClient.profileExists` | `GET /users?address=...` |
| 创建用户档案 | `GammaClient.createProfile` | `POST /profiles` |
| 幂等组合 | `GammaClient.ensureProfile` | 上面两个组合 |
| Relayer V2 鉴权 | `DepositWalletRelayer` 的所有方法 | `/submit` / `/nonce` / `/transaction` 都带 cookie；负责 deposit wallet 部署 + batch approval 提交 |
| Onboarder 流程串联 | `Onboarder.deployIfNeeded` / `applyApprovals` / `buildRelayer` | 一次登录，三步复用 |

### 2.2 不参与什么

- **CLOB 鉴权**：CLOB 完全不认这把 cookie。
- **链上交易直签**：cookie 只是让 relayer 信任「这个请求来自登录的 EOA」；真正"我授权了这批 calls"靠的是 EIP-712 batch 签名。

---

## 3. EOA 签名：信任的种子

EOA 私钥用一次 ECDSA 算法对不同结构化数据签 65 字节 `r‖s‖v`，不同形态对应不同用途。

### 3.1 形态与场景

| 签名形态 | 被签数据 | 用途 | 代码入口 |
|---|---|---|---|
| EIP-191 personal_sign | SIWE 文本（含 nonce、chainId、过期时间） | 换 `GammaSession` | `GammaClient.loginWithSiwe` 内 `personalSignDigest` |
| EIP-712 (CTF Exchange Order) | Order struct (uint256 salt, address maker/signer, uint256 tokenId, …) | 直签订单（EOA 模式） | `EIP712OrderSigner.sign[V2]` |
| EIP-1271 (Pol1271) | 同上 Order struct | 由 deposit wallet 代理验证签名（`POLY_1271` 签名类型） | `Pol1271OrderSigner.sign` |
| EIP-712 (Batch) | `Batch{wallet, nonce, deadline, calls[]}` | 让 deposit wallet 在链上执行一组 `Call`（部署后的批量 approve、原则上也包括 redeem / 转账） | `BatchEip712.hashBatch` + `Signer.signHash` |
| EIP-712 (ClobAuth) | `clob-derive-api-key` 域 | 派生 / 取出 CLOB API key 三件套 | `auth/ClobAuth.java`（被 `AuthApi` 用到） |
| EOA 链上 tx | 任意 tx（`redeemPositions` / 直接转账等） | 链上交互；本仓未封装 redeem，需自己组 tx | `Web3jEvmRpcClient` 或外部工具 |

### 3.2 关键点

- 失去 EOA 私钥 = 三条线全断（cookie 续不上、订单签不了、新 API key 派生不了）。
- 单次签名一次性使用：批量场景靠 `wallet.nonce()` 防重放，订单靠订单 salt + 链上 nonce 防重放。
- EIP-1271 模式下，链上 `isValidSignature` 验证由 deposit wallet 实现执行，但底层依然是 EOA 签的字节——deposit wallet 只代理校验，并不替代 EOA 的私钥角色。

---

## 4. HMAC L2 头：CLOB 撮合服务的门票

```
POLY_ADDRESS    = caller.toLowerHex()
POLY_API_KEY    = creds.apiKey
POLY_PASSPHRASE = creds.passphrase
POLY_SIGNATURE  = base64url(HMAC-SHA256(base64url_decode(secret), ts + method + path + body))
POLY_TIMESTAMP  = ts (unix seconds)
```

实现见 `auth/L2HeaderBuilder.java`；CLOB API 的几乎所有调用（下 / 撤 / 查个人订单 / 评分 / 余额）都依赖这把头。

### 4.1 几条容易踩的细节

- **HMAC 输入的 `path` 不含 query**：`isOrderScoring` 等带 query 的端点，HMAC 仍签裸 path（与 py / rs 客户端对齐）。
- **body 必须与最终发出的 JSON 字节完全一致**：`OrderApiImpl` 因此把 body 字符串"算一次、用两次"（喂 HMAC + 喂 HTTP body），避免 Jackson 重序列化漂移。
- **secret 是 URL-safe base64**（带 `=` padding），先 base64 解码再做 HMAC；签名输出再 URL-safe base64 编码。
- **timestamp 单位是秒**，服务端有偏差窗口，本机时钟漂移过大会 401。

### 4.2 HMAC 与 EOA 签名的关系

- HMAC 让 CLOB 网关接受这次 HTTP 请求（"谁能调"）。
- EOA 签的订单 struct 走在 body 里，由 Exchange 链上验证（"这单是不是这个 maker 真的下的"）。
- 撤单只动 CLOB 内部订单簿状态、不上链，所以**只剩 HMAC 这一层**。

---

## 5. 端到端串联：典型一笔业务怎么走完

```
[ EOA private key ]
        │
        │ ① SIWE personal_sign           ──► Gamma /login
        │                                       │
        │                                       ▼
        │                                 GammaSession (cookie)
        │                                       │
        │                                       ▼
        │ ② EIP-712 batch sign (calls)   ──► Relayer /submit  (代付 gas, deploy + approve)
        │
        │ ③ EIP-712 (ClobAuth) sign      ──► CLOB /auth/api-key
        │                                       │
        │                                       ▼
        │                                 ApiCredentials (key/secret/passphrase)
        │                                       │
        │                                       ▼
        │ ④ EIP-712 / EIP-1271 order sig ──► CLOB POST /order   (header: HMAC L2 头  + body: SignedOrder)
        │
        │ ⑤ — —                          ──► CLOB DELETE /order (header: HMAC L2 头, 无 EOA 签名)
        │
        └ ⑥ EOA tx (or via deposit batch) ──► CTF redeemPositions  (链上, 不经 Gamma/CLOB)
```

对应到代码：

| 阶段 | 凭证 | 主要文件 |
|---|---|---|
| ① 登录 | EOA 签 SIWE → 生成 `GammaSession` | `gamma/GammaClient.java` |
| ② 部署+授权 | `GammaSession` + EOA batch 签名 | `deposit/DepositWalletRelayer.java`、`deposit/BatchEip712.java`、`onboard/Onboarder.java` |
| ③ 派生 API key | EOA EIP-712 签名 | `api/AuthApiImpl.java`、`auth/ClobAuth.java` |
| ④ 下单 | EOA 订单签名 + HMAC L2 头 | `order/EIP712OrderSigner.java`、`order/Pol1271OrderSigner.java`、`api/OrderApiImpl.java` |
| ⑤ 撤单 | 仅 HMAC L2 头 | `api/OrderApiImpl.java`（`cancelOrder` / `cancelOrders` / `cancelAll` / `cancelMarketOrders`） |
| ⑥ 赎回 | EOA tx（直接发）或 EOA batch 签 + `GammaSession`（走 deposit wallet relayer） | 仓内**未封装**；可复用 ② 的通道，把 `redeemPositions` calldata 当作一笔 `Call` 走 batch |

---

## 6. 操作 → 凭证速查

| 操作 | EOA 签名 | HMAC L2 头 | GammaSession | 备注 |
|---|---|---|---|---|
| Gamma 用户档案读写 | / | / | ✅ | 仅 cookie |
| 部署 deposit wallet | / | / | ✅ | `WALLET-CREATE` |
| 批量授权 (approve / setApprovalForAll) | ✅ EIP-712 batch | / | ✅ | EIP-712 batch + cookie |
| 派生 / 取回 CLOB API key | ✅ EIP-712 (ClobAuth) | / | / | 一次派生，长期使用 |
| 下单 `POST /order(s)` | ✅ EIP-712 / EIP-1271 | ✅ | / | 两层签名串联 |
| 撤单 `DELETE /order(s)` `/cancel-all` `/cancel-market-orders` | / | ✅ | / | 撤单不上链 |
| 查询个人订单 / 余额 / scoring | / | ✅ | / | L2 鉴权 |
| 查询行情 / 市场列表 | / | / | / | 公开端点 |
| CTF `redeemPositions` (链上) | ✅ EOA tx | / | / 或 ✅ | 直接发链上 tx 不需 cookie；走 deposit-wallet batch 则需 |

---

## 7. 边界与坑

1. **`GammaSession` ↔ HMAC 不通用**。Gamma / Relayer 不认 HMAC；CLOB 不认 cookie。
2. **EOA 签名是种子**。三件事最终都要追溯到 EOA：SIWE 出 cookie，ClobAuth 出 HMAC，OrderSigner 出订单合法性。
3. **撤单是唯一只剩 HMAC 的操作**——纯链下、纯改撮合服务内部状态。
4. **过期复杂度**：HMAC（长期）< EOA（一次性，但私钥稳定）< GammaSession（7 天）。生产里 cookie 是最容易"突然失效"的那把。
5. **Relayer 的 cookie ≠ 链上 tx 的签名**。Relayer 用 cookie 验「这是登录的 EOA 在请求」，再用 EIP-712 batch 签名验「这批 calls 真的被 EOA 授权」——两层共同决定 relayer 是否替你发链上 tx 并代付 gas。
6. **HMAC 输入要"原样"**：服务端按字节重算 HMAC，body 字段顺序、空白、引号都不能变；本仓 `OrderApiImpl` 的"算一次、用两次"模式是为了规避 Jackson 重序列化漂移。
7. **EIP-1271 不是 EOA 的替身**。`POLY_1271` 模式下订单的 `signer` 字段是 EOA，`maker` 字段是 deposit wallet；签名仍是 EOA 私钥签，只是链上由 deposit wallet 实现的 `isValidSignature` 校验。

---

## 8. `GammaSession` 过期影响面

`GammaSession` 默认 7 天过期。过期后并不会让"已经做完的链上状态"回滚（部署/授权都是 on-chain 一次性事件），影响面只覆盖**还需要 Polymarket 后台代办**的操作。

### 8.1 下单是否受影响

**不受影响**。整条下单链路（`order/` + `api/OrderApiImpl.java` + `auth/L2HeaderBuilder.java`）没有任何一处引用 `GammaSession`。只要满足以下三个前提，订单仍可正常下、撤、查：

| 前提 | 凭证 | 失效后能否自行恢复（无需 cookie） |
|---|---|---|
| ① CLOB API key 三件套有效 | `ApiCredentials` (HMAC) | ✅ 可用 EOA EIP-712 (ClobAuth) 重新派生 |
| ② Deposit wallet 已部署 | 链上状态 | ✅ 一次性事件，已部署即长期有效 |
| ③ 该单需要的 ERC-20 / CTF approval 已 set | 链上状态 | ✅ 同上 |

### 8.2 过期 cookie 后各操作的可用性

| 操作 | cookie 过期后 | 不可用时的恢复路径 |
|---|---|---|
| 下单 `POST /order` | ✅ 可用 | — |
| 撤单 `DELETE /order` 等 | ✅ 可用 | — |
| 查个人订单 / 余额 / scoring | ✅ 可用 | — |
| 派生 / 取回 CLOB API key | ✅ 可用（仅需 EOA EIP-712 签名） | — |
| 链上直接发 tx（含 EOA 直发 `redeemPositions`） | ✅ 可用 | — |
| **新部署 deposit wallet** | ❌ 走 Relayer V2，要 cookie | 重新 SIWE 登录 |
| **批量 approve / setApprovalForAll**（含未来新增 spender） | ❌ 同上 | 同上 |
| **走 deposit wallet relayer 做 redeem** | ❌ 同上 | 重新 SIWE；或绕开 relayer 由 EOA 直发 CTF（自付 gas） |
| **更新 Gamma 用户档案** | ❌ 走 Gamma 后端，要 cookie | 重新 SIWE 登录 |

### 8.3 实战部署建议

- onboarding 流程（SIWE → 部署 → batch approve → 派生 API key）一次性跑完后，可以**立即丢弃 cookie**，常驻交易服务只持有 `(EOA 私钥, ApiCredentials)` 两件套——既缩小密钥暴露面，也免维护 7 天 cookie 续期。
- 仅当需要再次让 Polymarket 后台代付链上 gas（新增授权、追加 deposit wallet、走 relayer 赎回等）时才重新走 SIWE 登录。
- 一句话：**只要"链上前置工作"做完，订单生命周期永远不再依赖 `GammaSession`**。
