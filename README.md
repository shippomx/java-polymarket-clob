# Polymarket CLOB Java Client

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.org/projects/jdk/17/)
[![Maven](https://img.shields.io/badge/build-maven-blue.svg)](https://maven.apache.org/)
[![Status](https://img.shields.io/badge/status-0.1.0--SNAPSHOT-yellow.svg)]()

Polymarket CLOB（中央限价订单簿）API 的 Java 17 SDK，支持 V1 及 V2 版本，覆盖：

- REST 端点（市场数据 / 认证 / 账户 / 下单 / 成交 / 心跳 / Builder）
- WebSocket 订阅（行情 + 用户态）
- EIP-712 订单签名（CTF Exchange v1 + v2，2026-04-28 起 Polygon 主网默认 v2）
- Gasless 链上流水线（Builder-Relayer + Gnosis Safe）
- L1 / L2 头部构造，Safe / EOA / Proxy / EIP-1271 全签名类型

GroupId / ArtifactId：`com.polymarket:clob-client`

---

## 目录

- [快速开始](#快速开始)
- [核心概念：Typestate 客户端](#核心概念typestate-客户端)
- [模块概览](#模块概览)
- [使用示例](#使用示例)
  - [1. 只读市场数据](#1-只读市场数据)
  - [2. 认证 + 账户查询](#2-认证--账户查询)
  - [3. 下单 / 撤单 / 查询](#3-下单--撤单--查询)
  - [4. WebSocket 订阅](#4-websocket-订阅)
  - [5. 心跳维持订单优先级](#5-心跳维持订单优先级)
  - [6. Builder 模式](#6-builder-模式)
  - [7. Gasless 链上流水线](#7-gasless-链上流水线)
- [可运行示例](#可运行示例)
- [构建与测试](#构建与测试)
- [技术栈与依赖](#技术栈与依赖)
- [线程安全与生命周期](#线程安全与生命周期)
- [异常体系](#异常体系)

---

## 快速开始

### 前置要求

- JDK 17+
- Maven 3.8+
- 可选：Polygon EOA 私钥（仅认证 / 下单场景需要）

### 安装

```bash
git clone <repo-url>
cd clob-java
mvn -B clean install
```

### 最小用法

```java
import com.polymarket.clob.ClobClient;
import com.polymarket.clob.model.ChainId;

try (ClobClient client = ClobClient.builder()
        .useDefaultEndpoint()
        .chainId(ChainId.POLYGON)
        .build()) {

    System.out.println("server time = " + client.market().serverTime().join());
}
```

---

## 核心概念：Typestate 客户端

SDK 用三段式 typestate 在编译期阻断"未认证调用受保护端点"或"未升级 Builder 调 Builder 端点"：

```
              authenticate(signer, sigType, ...)        promoteToBuilder(BuilderConfig)
ClobClient ──────────────────────────────────► AuthenticatedClobClient ─────────────────────────► BuilderClobClient
(只读)                                          (L1+L2 已认证)                                     (Builder 头叠加)
```

| 客户端 | 能力 | 备注 |
|--------|------|------|
| `ClobClient` | 只读市场数据、健康检查、版本号 | 入口；通过 `ClobClient.builder()` 构造 |
| `AuthenticatedClobClient` | L1/L2 凭证下的下单、撤单、账户、成交、心跳、API Key 管理 | 共享底层 transport |
| `BuilderClobClient` | 在已认证基础上叠加 `POLY_BUILDER_*` 头部，访问 `/builder/trades`、`/auth/builder-api-key` | 组合而非继承，可随时回退 `authenticated()` |

升级路径细节：

- `ClobClient#authenticate(signer, sigType, ApiCredentials)` —— 已有凭证时直接升级，零网络调用。
- `ClobClient#authenticate(signer, sigType, BigInteger nonce)` —— 自动调 `POST /auth/api-key` 失败回落 `GET /auth/derive-api-key`，幂等获取凭证。
- `AuthenticatedClobClient#promoteToBuilder(BuilderConfig)` —— 附加 Builder 凭证，不会吊销原 L2 凭证。

---

## 模块概览

```
com.polymarket.clob
├── ClobClient                       # 只读入口（typestate · level 0）
├── ClobClientBuilder                # fluent builder
├── AuthenticatedClobClient          # L1/L2 已认证（level 1）
├── BuilderClobClient                # Builder 模式（level 2）
│
├── api/                             # REST 接口
│   ├── MarketDataApi                # /time /version /midpoint /price /book /markets/{id} /tick-size /neg-risk /fee-rate
│   ├── AuthApi                      # /auth/api-key (create / derive / list / delete)
│   ├── AccountApi                   # /balance-allowance /auth/ban-status/closed-only
│   ├── OrderApi                     # /order /orders /cancel-* /data/order /data/orders /order-scoring V1+V2
│   ├── TradeApi                     # /data/trades /builder/trades
│   ├── HeartbeatApi                 # POST /v1/heartbeats
│   ├── BuilderApi                   # /auth/builder-api-key (create / list / revoke)
│   └── model/                       # 响应 DTO + 枚举（Side / TraderSide / TickSize / Token / ...）
│
├── auth/                            # 认证 / 签名底层
│   ├── Signer / LocalSigner         # 抽象签名器 + 本地私钥实现（web3j）
│   ├── ApiCredentials               # apiKey / secret / passphrase 三元组
│   ├── ClobAuth                     # ClobAuth EIP-712 typed data
│   ├── L1HeaderBuilder              # POLY_ADDRESS/SIGNATURE/TIMESTAMP/NONCE
│   ├── L2HeaderBuilder              # POLY_API_KEY/PASSPHRASE/TIMESTAMP/SIGNATURE (HMAC)
│   ├── WalletDerivation             # EOA → Polymarket Safe / Proxy 派生
│   ├── SignatureType                # EOA(0) / POLY_PROXY(1) / POLY_GNOSIS_SAFE(2) / POLY_1271(3)
│   └── builder/                     # BuilderConfig + BuilderHeaderBuilder
│
├── order/                           # 订单构造 / 签名 / 序列化
│   ├── OrderBuilder                 # create+sign 一站式
│   ├── EIP712OrderSigner            # CTF Exchange v1 / v2 EIP-712 摘要
│   ├── LimitOrderArgs / MarketOrderArgs (+V2)
│   ├── Order / SignedOrder / OrderV2 / SignedOrderV2
│   ├── OrderType                    # GTC / GTD / FOK / FAK
│   ├── TickSize / RoundConfig / OrderRounding / Amount
│   ├── CreateOrderOptions
│   ├── SaltSource                   # secureRandom / fixed（parity 测试用）
│   └── OpenOrder / OpenOrderParams / *Response
│
├── ws/                              # WebSocket 订阅
│   ├── ClobWebSocketClient          # 行情（无需认证）
│   ├── AuthenticatedClobWebSocketClient  # 用户态（订单 / 成交）
│   ├── ChannelGateway / WsConnection / ConnectionState
│   ├── WebSocketConfig              # 连接超时 / 重连退避
│   ├── Subscription / SubscriptionListener
│   ├── message/                     # BookUpdate / PriceChange / TickSizeChange / TradeMessage / OrderMessage / LastTradePrice
│   └── request/                     # Channel / Operation / SubscriptionRequest
│
├── gasless/                         # 链上 gasless 流水线（Builder-Relayer 委托）
│   ├── GaslessRelayer               # 主入口：deploy / execute / setupApprovals / prepare* + submit
│   ├── SafeEip712                   # Safe v1.3.0 execTransaction + SafeProxyFactory createProxy 摘要
│   ├── SafeSignatures               # ECDSA v 调整、yParity 归一
│   ├── MultiSend                    # Gnosis MultiSend payload 编码
│   ├── Calldata                     # ERC20 approve/transfer、ERC1155 setApprovalForAll、CTF redeemPositions
│   ├── RelayerTx / SafeTxPayload / SafeTxStyle / RelayerTxResult
│   └── Words                        # 32-byte 字节工具
│
├── http/                            # 传输底层
│   ├── HttpTransport                # 基于 java.net.http.HttpClient
│   ├── JsonCodec / ClobTypesModule  # Jackson 配置 + 自定义类型（BigInteger / hex / Side / ...）
│   ├── QueryEncoder                 # query 串拼接（与上游字段顺序一致）
│   ├── CursorPager                  # 游标分页 → Stream 适配
│   └── RequestCaptor                # parity 测试用 wire-level 抓包钩
│
├── model/                           # 通用领域模型
│   ├── Address / Hash32             # 0x… EVM 类型，强校验
│   ├── ChainId                      # POLYGON=137 / AMOY=80002
│   ├── ContractConfig / ContractRegistry / WalletContractConfig
│   ├── AssetType                    # COLLATERAL / CONDITIONAL
│   └── BalanceAllowance* / BanStatusResponse / ApiKeysResponse
│
├── trade/                           # 成交模型
│   ├── Trade / BuilderTrade / MakerOrder
│   └── TradesRequest                # 过滤条件
│
├── heartbeat/                       # 心跳调度
│   ├── HeartbeatResponse
│   └── HeartbeatScheduler           # 后台自动维护 heartbeat_id 链
│
├── exception/                       # 受检层级
│   ├── ClobException (base)
│   ├── ClobAuthException
│   ├── ClobApiException             # 上游 4xx/5xx
│   ├── ClobTransportException       # 网络 / IO
│   ├── ClobSerializationException
│   └── ClobSignatureException
│
└── example/                         # 9 个开箱即用 main(String[]) 演示
```

---

## 使用示例

### 1. 只读市场数据

```java
import com.polymarket.clob.ClobClient;
import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.model.ChainId;

try (ClobClient client = ClobClient.builder()
        .useDefaultEndpoint()                   // https://clob.polymarket.com
        .chainId(ChainId.POLYGON)
        .build()) {

    String tokenId = "12345...";
    var book = client.market().getOrderBook(tokenId).join();
    var mid = client.market().getMidpoint(tokenId).join();
    var bid = client.market().getPrice(tokenId, Side.BUY).join();
    var tick = client.market().getTickSize(tokenId).join();
    var negRisk = client.market().getNegRisk(tokenId).join();
    var feeBps = client.market().getFeeRateBps(tokenId).join();

    System.out.printf("mid=%s buy=%s tick=%s neg=%s fee=%dbps%n",
            mid.getMid(), bid.getPrice(), tick.wireValue(), negRisk, feeBps);
}
```

### 2. 认证 + 账户查询

```java
import com.polymarket.clob.AuthenticatedClobClient;
import com.polymarket.clob.ClobClient;
import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.model.AssetType;
import com.polymarket.clob.model.BalanceAllowanceRequest;
import com.polymarket.clob.model.ChainId;

import java.math.BigInteger;

var signer = LocalSigner.fromPrivateKey(System.getenv("CLOB_PRIVATE_KEY"));

try (ClobClient base = ClobClient.builder()
        .useDefaultEndpoint()
        .chainId(ChainId.POLYGON)
        .build()) {

    // 自动 createOrDerive 拿 L2 凭证
    AuthenticatedClobClient client = base
            .authenticate(signer, SignatureType.POLY_PROXY, BigInteger.ZERO)
            .join();

    var resp = client.balanceAllowance(BalanceAllowanceRequest.builder()
            .assetType(AssetType.COLLATERAL)
            .build())
            .join();

    System.out.println("USDC balance = " + resp.balance());
}
```

支持的签名类型（与 funder 派生方式）：

| `SignatureType` | code | funder 派生 | 适用场景 |
|-----------------|------|-------------|----------|
| `EOA`              | 0 | EOA 自身 | 直签下单，资金留在 EOA |
| `POLY_PROXY`       | 1 | CREATE2 派生 Proxy | Polymarket 标准用户钱包 |
| `POLY_GNOSIS_SAFE` | 2 | Safe Proxy Factory | Safe 1/N owner |
| `POLY_1271`        | 3 | 调用方提供 | 仅 V2 订单；EIP-1271 智能合约签名 |

### 3. 下单 / 撤单 / 查询

```java
import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.order.*;

import java.math.BigDecimal;
import java.math.BigInteger;

BigInteger tokenId = new BigInteger("12345...");

// 准备 tick + neg-risk 元数据
var tick = client.market().getTickSize(tokenId.toString()).join();
var negRisk = client.market().getNegRisk(tokenId.toString()).join();
var opts = CreateOrderOptions.of(tick, negRisk);

// 限价单：构造 → 签名 → POST /order（GTC + postOnly=false）
var args = LimitOrderArgs.builder()
        .tokenId(tokenId)
        .price(new BigDecimal("0.42"))
        .size(new BigDecimal("100"))
        .side(Side.BUY)
        .build();

var resp = client.createAndPostLimitOrder(args, opts, OrderType.GTC, false).join();
System.out.println("orderId = " + resp.orderId());

// 查询开放订单（自动游标分页 → Stream）
client.getOpenOrders(OpenOrderParams.builder().assetId(tokenId.toString()).build())
        .forEach(o -> System.out.println(o.getId() + " " + o.getStatus()));

// 撤单
client.cancelOrder(resp.orderId()).join();
```

V2 路径（CTF Exchange v2，2026-04-28 起 Polygon 主网默认）：

```java
import com.polymarket.clob.order.LimitOrderArgsV2;

var argsV2 = LimitOrderArgsV2.builder()
        .tokenId(tokenId)
        .price(new BigDecimal("0.42"))
        .size(new BigDecimal("100"))
        .side(Side.BUY)
        .build();

var v2 = client.createAndPostLimitOrderV2(argsV2, opts, OrderType.GTC, false).join();
```

> 启动时可调一次 `client.market().serverVersion()` 检测协议版本，按 `1` / `2` 决定走 V1 还是 V2。

### 4. WebSocket 订阅

公开行情（无需认证）：

```java
import com.polymarket.clob.ws.*;
import com.polymarket.clob.ws.message.BookUpdate;

try (ClobWebSocketClient ws = ClobWebSocketClient.create(
        ClobWebSocketClient.DEFAULT_ENDPOINT,    // wss://ws-subscriptions-clob.polymarket.com
        WebSocketConfig.defaults())) {

    Subscription sub = ws.subscribeOrderbook(List.of(tokenId), new SubscriptionListener<>() {
        @Override public void onMessage(BookUpdate msg) {
            System.out.printf("bids=%d asks=%d%n", msg.bids().size(), msg.asks().size());
        }
        @Override public void onError(Throwable err) { err.printStackTrace(); }
    });

    Thread.sleep(30_000);
    sub.cancel();
}
```

用户态（订单 / 成交，需先认证）：

```java
try (var ws = authenticatedClient.webSocket()) {
    ws.subscribeOrders(List.of(conditionId), msg -> System.out.println("order: " + msg));
    ws.subscribeTrades(List.of(conditionId), msg -> System.out.println("trade: " + msg));
    Thread.sleep(60_000);
}
```

支持四类公开订阅 + 两类用户订阅，连接断开后内置指数退避自动重连，重连时重新发送 `subscribe` 帧。

### 5. 心跳维持订单优先级

```java
import com.polymarket.clob.heartbeat.HeartbeatScheduler;

try (HeartbeatScheduler hb = client.startHeartbeats(Duration.ofSeconds(5))) {
    // ... 业务逻辑
}
// close() 会优雅 stop()，自动释放后台线程
```

或单次手动调用：

```java
var resp = client.postHeartbeat(null).join();
UUID id = resp.heartbeatId();
// 下次调用回传 id 维持链路
client.postHeartbeat(id).join();
```

### 6. Builder 模式

```java
import com.polymarket.clob.BuilderClobClient;
import com.polymarket.clob.auth.builder.BuilderConfig;

// 本地凭证模式
BuilderConfig cfg = BuilderConfig.local(new ApiCredentials(
        System.getenv("BUILDER_API_KEY"),
        System.getenv("BUILDER_API_SECRET"),
        System.getenv("BUILDER_PASSPHRASE")));

// 或：远程签名模式（BE 服务把签名委托给独立钱包服务）
// BuilderConfig cfg = BuilderConfig.remote("https://signer-host", token);

BuilderClobClient builder = authenticatedClient.promoteToBuilder(cfg);

builder.getBuilderTrades(TradesRequest.none())
        .limit(10)
        .forEach(t -> System.out.println(t.getId()));

builder.listBuilderApiKeys().join().forEach(System.out::println);
```

### 7. Gasless 链上流水线

通过 Polymarket Builder-Relayer 委托所有链上动作（部署 Safe、approve、execTransaction），EOA **不需要持有 MATIC**：

```java
import com.polymarket.clob.gasless.*;
import com.polymarket.clob.auth.builder.BuilderConfig;

GaslessRelayer relayer = GaslessRelayer.create(
        URI.create("https://relayer-v2.polymarket.com"),
        BuilderConfig.local(builderCreds));

// 1) 检测 Safe 是否已部署
boolean deployed = relayer.isDeployed(safeAddress).join();

// 2) 未部署：发起 SAFE-CREATE
if (!deployed) {
    RelayerTxResult r = relayer.deploy(safeAddress, signer).join();
    relayer.waitForTx(r.transactionId()).join();
}

// 3) 一次性把 USDC.approve + CTF.setApprovalForAll 等 6 笔授权打包成一条 SafeTx（nonce=0）
RelayerTxResult ap = relayer.setupApprovals(safeAddress, signer).join();
relayer.waitForTx(ap.transactionId()).join();
```

参见完整端到端示例 `EndToEndOnboardingExample`：从 EOA 私钥起步，自举 L2 凭证 + Builder Key + Safe 部署 + 授权 + V2 下单。

---

## 可运行示例

`src/main/java/com/polymarket/clob/example/` 下 9 个 `main(String[])` 入口：

| 类 | 用途 | 关键环境变量 |
|----|------|-------------|
| `UnauthenticatedExample` | 公开行情 | `(可选 token_id 作为参数)` |
| `AuthenticatedExample` | L1/L2 认证 + 账户查询 | `CLOB_PRIVATE_KEY` `CLOB_SIGNATURE_TYPE` |
| `TradingExample` | 限价单 / 市价单 / 撤单（默认 dry-run） | `CLOB_PRIVATE_KEY` `CLOB_TOKEN_ID` `CLOB_SUBMIT=1` |
| `WebSocketOrderBookExample` | market / user 双模式 WS | `MODE` `ASSET_IDS` `MARKETS` `DURATION_SECONDS` |
| `HeartbeatExample` | 单次 / 调度模式心跳 | `MODE=once|scheduler` |
| `BuilderExample` | promote 到 Builder 客户端 | `BUILDER_API_KEY/SECRET/PASSPHRASE` 或 `BUILDER_REMOTE_HOST` |
| `SafeWalletExample` | 链上自付 gas 部署 Safe + 授权 | 需要 EOA 持有 MATIC |
| `EndToEndOnboardingExample` | 完整 gasless 冷启动流水线 | `CLOB_PRIVATE_KEY` + Polymarket relayer 凭证 |
| `PolymarketBridge` | Bridge 充值地址查询 | （SDK 工具类，非 main） |

运行：

```bash
export CLOB_PRIVATE_KEY=0xac0974...
mvn -B compile exec:java \
    -Dexec.mainClass=com.polymarket.clob.example.AuthenticatedExample
```

> 所有示例默认 `Polygon` 主网，可通过 `CLOB_CHAIN_ID=AMOY` 切到测试网。涉及下单的示例默认 dry-run，必须显式 `CLOB_SUBMIT=1` 才会真正提交订单。

---

## 构建与测试

```bash
# 编译
mvn -B clean compile

# 单元测试 + 集成测试（含嵌入式 WebSocket、WireMock REST mock、parity diff）
mvn -B test

# 完整 verify（含 jacoco 覆盖率报告）
mvn -B verify

# 启用 OWASP dependency-check（首次需下载 NVD 数据，建议传 API Key）
mvn -B -Psecurity -DnvdApiKey=$NVD_API_KEY verify
```

测试基础设施位于 `src/test/java/com/polymarket/clob/parity/`，把 SDK 的 wire 输出（HTTP 请求 / WS 帧 / EIP-712 摘要）与官方 `py-clob-client` / `rs-clob-client` 的 golden 向量做 byte-level diff，保证跨语言行为一致。

---

## 技术栈与依赖

运行时依赖（生产路径）：

| 依赖 | 版本 | 用途 |
|------|------|------|
| Jackson (databind / jsr310 / parameter-names) | 2.18.0 | JSON 序列化 |
| web3j-core | 4.12.2 | EIP-712 / ECDSA / ABI 编码 |
| Lombok | 1.18.34 | 编译期 boilerplate（`provided` scope） |
| SLF4J API | 2.0.16 | 日志门面（运行时由调用方绑定实现） |
| **JDK `java.net.http.HttpClient`** | JDK 17 | REST 传输 |
| **JDK `java.net.http.WebSocket`** | JDK 17 | WS 传输（生产路径无第三方 WS 库） |

测试 scope（不进生产 jar）：

JUnit Jupiter 5、AssertJ、Mockito、WireMock、Logback、Java-WebSocket（嵌入式服务端 mock）、zjsonpatch（parity diff 渲染）。

---

## 线程安全与生命周期

- `ClobClient` / `AuthenticatedClobClient` / `BuilderClobClient`：**线程安全**，所有字段 `final`，可在多个业务线程间复用。
- `ClobClientBuilder`：**非线程安全**，仅初始化阶段单线程使用。
- `OrderBuilder`：**线程安全**，无可变状态，可在多线程下并发构造订单。
- `HeartbeatScheduler`：内部单线程 daemon `ScheduledExecutorService`，调用 `close()` 优雅停止。
- WebSocket 客户端：所有 `subscribe*` 可并发；底层 connection 首次订阅时 lazy 建立。`close()` 后未取消的订阅句柄全部失效。
- `close()` 当前在 JDK 17 基线下是 no-op（`HttpClient` 由 GC + JVM 关闭钩子兜底，未来 JDK 21+ 升级会下沉真正的资源释放）。

---

## 异常体系

```
ClobException (RuntimeException)
├── ClobAuthException           # 凭证无效 / funder 派生失败
├── ClobApiException            # 上游 HTTP 4xx/5xx，含 status + body
├── ClobTransportException      # 网络 / IO / 超时
├── ClobSerializationException  # JSON 编解码错误
└── ClobSignatureException      # EIP-712 / HMAC 计算错误
```

`CompletableFuture` 路径上失败会以这些异常 `completeExceptionally`；`get()` / `join()` 调用方应捕获 `ExecutionException` / `CompletionException` 后 unwrap。

---

## 参考链接

- Polymarket CLOB API: <https://docs.polymarket.com/>
- 官方 Python 客户端: <https://github.com/Polymarket/py-clob-client>
- 官方 Rust 客户端: <https://github.com/Polymarket/rs-clob-client>
- Builder-Relayer 协议: <https://relayer-v2.polymarket.com>
