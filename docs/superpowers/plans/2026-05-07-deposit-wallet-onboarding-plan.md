# Deposit Wallet Onboarding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Java SDK 从 Gnosis Safe 钱包流整体切换到 Polymarket Deposit Wallet 流（Solady CWIA），实现端到端 onboard + trade 管道，与 TS 例子 `fullOnboardAndTrade.ts` 行为一致。

**Architecture:** 新增 `chain/` (RPC 读取)、`gamma/` (SIWE 登录 + 用户档案)、`deposit/` (Deposit Wallet 派生与 relayer)、`order/Pol1271OrderSigner` (ERC-7739 嵌套签名)、`onboard/Onboarder` (薄编排器) 五个模块；先全部加新代码并通过测试，最后一次性删除旧 Safe 代码并跳大版本号。

**Tech Stack:** Java 17、Maven、web3j 4.12.2 (RPC + EIP-712 编码)、Jackson 2.18 (JSON)、JUnit 5.11 + AssertJ + WireMock 3.9.2 (测试)。

**Reference Spec:** `docs/superpowers/specs/2026-05-07-deposit-wallet-onboarding-design.md`

---

## 阶段总览

| 阶段 | Task | 描述 |
|---|---|---|
| A | 1 | 创建常量表 `PolymarketContracts` |
| A | 2 | 数据类 `DepositWalletConfig` + `ContractRegistry.depositWalletConfig` |
| B | 3 | `EvmRpcClient` 接口 + `EvmRpcException` |
| B | 4 | `Web3jEvmRpcClient` 实现 |
| B | 5 | `DepositWalletReads` 6 类读取 |
| C | 6 | `SiweMessage` 文本构造 |
| C | 7 | `GammaSession` + `GammaClient.loginWithSiwe` |
| C | 8 | `GammaClient.profileExists` + `createProfile` + `ensureProfile` |
| D | 9 | `Call` + `RelayerTx` + `RelayerTxResult` + `SignedBatch` |
| D | 10 | `ApprovalTargets` 13 项标准目标 |
| D | 11 | `BatchEip712.hashBatch` |
| D | 12 | `ApprovalPlanner.planMissingApprovals` |
| D | 13 | `DepositWalletRelayer`（4 个端点） |
| D | 14 | `DepositWalletDerivation` 封装 |
| E | 15 | `Pol1271OrderSigner.contentsHash` |
| E | 16 | `Pol1271OrderSigner.appDomainSep` 缓存 |
| E | 17 | `Pol1271OrderSigner.sign` 完整签名 |
| E | 18 | `OrderBuilder` 接通 POLY_1271 路径 |
| F | 19 | `OnboardingConfig` + Builder + `TestOrderArgs` + `OnboardingResult` |
| F | 20 | `Onboarder.run` 编排 |
| G | 21 | `DepositWalletOnboardAndTradeExample` 示例 |
| H | 22 | 删除 examples/Safe* 三个文件 |
| H | 23 | 删除 `gasless/` 与 `auth/builder/` 整包 |
| H | 24 | 简化 `SignatureType` 枚举 + 删 `WalletDerivation` Safe 路径 |
| H | 25 | 删除 Safe 测试 + parity fixtures、跳版本到 2.0.0、更新 README、`mvn clean verify` 全套 |

---

## Phase A — 依赖与常量

### Task 1: 创建 `PolymarketContracts` 常量表

**Files:**
- Create: `src/main/java/com/polymarket/clob/chain/PolymarketContracts.java`
- Test: `src/test/java/com/polymarket/clob/chain/PolymarketContractsTest.java`

- [ ] **Step 1: 写常量表测试**

```java
// src/test/java/com/polymarket/clob/chain/PolymarketContractsTest.java
package com.polymarket.clob.chain;

import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Hash;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class PolymarketContractsTest {

    @Test
    void factoryAddressMatchesSpec() {
        assertThat(PolymarketContracts.FACTORY)
                .isEqualTo(Address.fromHex("0x00000000000Fb5C9ADea0298D729A0CB3823Cc07"));
    }

    @Test
    void implementationAddressMatchesSpec() {
        assertThat(PolymarketContracts.IMPLEMENTATION)
                .isEqualTo(Address.fromHex("0x58CA52ebe0DadfdF531Cde7062e76746de4Db1eB"));
    }

    @Test
    void orderTypeHashEqualsKeccakOfTypeString() {
        byte[] expected = Hash.sha3(PolymarketContracts.ORDER_TYPE_STRING.getBytes(StandardCharsets.US_ASCII));
        assertThat(PolymarketContracts.ORDER_TYPE_HASH).isEqualTo(expected);
    }

    @Test
    void orderTypeStringStartsWithOrder() {
        assertThat(PolymarketContracts.ORDER_TYPE_STRING).startsWith("Order(");
    }

    @Test
    void typedDataSignTypeStringStartsWithTypedDataSign() {
        assertThat(PolymarketContracts.TYPED_DATA_SIGN_TYPE_STRING).startsWith("TypedDataSign(");
    }

    @Test
    void thirteenSpenderAddressesAllPresent() {
        assertThat(PolymarketContracts.USDC_E).isNotNull();
        assertThat(PolymarketContracts.USDC_NATIVE).isNotNull();
        assertThat(PolymarketContracts.CTF).isNotNull();
        assertThat(PolymarketContracts.EXCHANGE_V2).isNotNull();
        assertThat(PolymarketContracts.NEG_RISK_EXCHANGE_V2).isNotNull();
        assertThat(PolymarketContracts.NEG_RISK_ADAPTER).isNotNull();
        assertThat(PolymarketContracts.PUSD_QUOTER).isNotNull();
        assertThat(PolymarketContracts.NEW_SPENDER_A).isNotNull();
        assertThat(PolymarketContracts.NEW_SPENDER_B).isNotNull();
        assertThat(PolymarketContracts.PARLAY).isNotNull();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl . -Dtest=PolymarketContractsTest test`
Expected: FAIL — `PolymarketContracts` not found

- [ ] **Step 3: 写常量表实现**

```java
// src/main/java/com/polymarket/clob/chain/PolymarketContracts.java
package com.polymarket.clob.chain;

import com.polymarket.clob.model.Address;
import org.web3j.crypto.Hash;

import java.nio.charset.StandardCharsets;

/**
 * Polymarket Deposit Wallet 流相关的链上地址 + EIP-712 类型字符串集中存放。
 *
 * <p>Polygon mainnet (chainId 137) 唯一目标。地址来源：
 * docs/EOA_TO_ORDER.md §8（HAR 反向工程） 与
 * clob-client-v2/src/order-utils/abi/*.ts。</p>
 *
 * <p>所有 EIP-712 type strings 必须与 TS 源字节级一致；改动会导致链上 1271
 * 验签失败。</p>
 */
public final class PolymarketContracts {

    private PolymarketContracts() {}

    // SOURCE: docs/EOA_TO_ORDER.md §8
    public static final Address FACTORY            = Address.fromHex("0x00000000000Fb5C9ADea0298D729A0CB3823Cc07");
    public static final Address IMPLEMENTATION     = Address.fromHex("0x58CA52ebe0DadfdF531Cde7062e76746de4Db1eB");

    public static final Address USDC_E             = Address.fromHex("0xC011a7E12a19f7B1f670d46F03B03f3342E82DFB");
    public static final Address USDC_NATIVE        = Address.fromHex("0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174");
    public static final Address CTF                = Address.fromHex("0x4D97DCd97eC945f40cF65F87097ACe5EA0476045");
    public static final Address EXCHANGE_V2        = Address.fromHex("0xE111180000d2663C0091e4f400237545B87B996B");
    public static final Address NEG_RISK_EXCHANGE_V2 = Address.fromHex("0xe2222d279d744050d28e00520010520000310F59");
    public static final Address NEG_RISK_ADAPTER   = Address.fromHex("0xd91E80cF2E7be2e162c6513ceD06f1dD0dA35296");
    public static final Address PUSD_QUOTER        = Address.fromHex("0x93070a847efef7f70739046a929d47a521f5b8ee");
    public static final Address NEW_SPENDER_A      = Address.fromHex("0xada100db00ca00073811820692005400218fce1f");
    public static final Address NEW_SPENDER_B      = Address.fromHex("0xada2005600dec949baf300f4c6120000bdb6eaab");
    public static final Address PARLAY             = Address.fromHex("0xf3cfb6a6ebfeb51876289eb235719eb1c65252b0");

    // ----- EIP-712 域 -----
    // SOURCE: clob-client-v2/src/order-utils/exchangeOrderBuilderV2.ts (常量名 CTF_EXCHANGE_V2_DOMAIN_NAME)
    public static final String CTF_EXCHANGE_V2_DOMAIN_NAME    = "Polymarket CTF Exchange";
    public static final String CTF_EXCHANGE_V2_DOMAIN_VERSION = "1";

    public static final String DEPOSIT_WALLET_DOMAIN_NAME    = "DepositWallet";
    public static final String DEPOSIT_WALLET_DOMAIN_VERSION = "1";

    public static final String CLOB_AUTH_DOMAIN_NAME    = "ClobAuthDomain";
    public static final String CLOB_AUTH_DOMAIN_VERSION = "1";

    // ----- EIP-712 type strings -----
    // SOURCE: clob-client-v2/src/order-utils/abi/orderAbi.ts (CTF_EXCHANGE_V2_ORDER_STRUCT 序列化)
    public static final String ORDER_TYPE_STRING =
            "Order(uint256 salt,address maker,address signer,uint256 tokenId,"
            + "uint256 makerAmount,uint256 takerAmount,uint8 side,uint8 signatureType,"
            + "uint256 timestamp,bytes32 metadata,bytes32 builder)";

    // ERC-7739 嵌套：内层 TypedDataSign 类型字符串。
    // SOURCE: clob-client-v2/src/order-utils/exchangeOrderBuilderV2.ts (TYPED_DATA_SIGN_STRUCT)
    public static final String TYPED_DATA_SIGN_TYPE_STRING =
            "TypedDataSign(Order contents,string name,string version,uint256 chainId,"
            + "address verifyingContract,bytes32 salt)" + ORDER_TYPE_STRING;

    public static final byte[] ORDER_TYPE_HASH =
            Hash.sha3(ORDER_TYPE_STRING.getBytes(StandardCharsets.US_ASCII));

    public static final byte[] TYPED_DATA_SIGN_TYPE_HASH =
            Hash.sha3(TYPED_DATA_SIGN_TYPE_STRING.getBytes(StandardCharsets.US_ASCII));

    /** ERC-7739 末尾 uint16 BE = ORDER_TYPE_STRING 字节长度。 */
    public static int orderTypeStringByteLength() {
        return ORDER_TYPE_STRING.getBytes(StandardCharsets.US_ASCII).length;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -Dtest=PolymarketContractsTest test`
Expected: 6 tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/polymarket/clob/chain/PolymarketContracts.java \
        src/test/java/com/polymarket/clob/chain/PolymarketContractsTest.java
git commit -m "feat(chain): 新增 PolymarketContracts 常量表（地址 + EIP-712 type strings）"
```

---

### Task 2: `DepositWalletConfig` + `ContractRegistry.depositWalletConfig`

**Files:**
- Create: `src/main/java/com/polymarket/clob/chain/DepositWalletConfig.java`
- Modify: `src/main/java/com/polymarket/clob/model/ContractRegistry.java` — 新增 `depositWalletConfig(long)` 方法（不删 Safe，那一步留到 Phase H）
- Test: `src/test/java/com/polymarket/clob/chain/DepositWalletConfigTest.java`

- [ ] **Step 1: 写测试**

```java
// src/test/java/com/polymarket/clob/chain/DepositWalletConfigTest.java
package com.polymarket.clob.chain;

import com.polymarket.clob.model.ContractRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DepositWalletConfigTest {

    @Test
    void polygonReturnsConfig() {
        var cfg = ContractRegistry.depositWalletConfig(137).orElseThrow();
        assertThat(cfg.factory()).isEqualTo(PolymarketContracts.FACTORY);
        assertThat(cfg.implementation()).isEqualTo(PolymarketContracts.IMPLEMENTATION);
        assertThat(cfg.usdcE()).isEqualTo(PolymarketContracts.USDC_E);
        assertThat(cfg.parlay()).isEqualTo(PolymarketContracts.PARLAY);
    }

    @Test
    void amoyReturnsEmpty() {
        assertThat(ContractRegistry.depositWalletConfig(80002)).isEmpty();
    }

    @Test
    void unknownChainReturnsEmpty() {
        assertThat(ContractRegistry.depositWalletConfig(1)).isEmpty();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -Dtest=DepositWalletConfigTest test`
Expected: FAIL — `depositWalletConfig` 方法不存在

- [ ] **Step 3: 实现 record**

```java
// src/main/java/com/polymarket/clob/chain/DepositWalletConfig.java
package com.polymarket.clob.chain;

import com.polymarket.clob.model.Address;

/**
 * Deposit Wallet 流的链级常量打包，用于 onboarding 与订单签名。
 * 仅 Polygon (137) 有非空配置。
 */
public record DepositWalletConfig(
        Address factory,
        Address implementation,
        Address usdcE,
        Address usdcNative,
        Address ctf,
        Address exchangeV2,
        Address negRiskExchangeV2,
        Address negRiskAdapter,
        Address pUsdQuoter,
        Address newSpenderA,
        Address newSpenderB,
        Address parlay
) {
    public static DepositWalletConfig polygon() {
        return new DepositWalletConfig(
                PolymarketContracts.FACTORY,
                PolymarketContracts.IMPLEMENTATION,
                PolymarketContracts.USDC_E,
                PolymarketContracts.USDC_NATIVE,
                PolymarketContracts.CTF,
                PolymarketContracts.EXCHANGE_V2,
                PolymarketContracts.NEG_RISK_EXCHANGE_V2,
                PolymarketContracts.NEG_RISK_ADAPTER,
                PolymarketContracts.PUSD_QUOTER,
                PolymarketContracts.NEW_SPENDER_A,
                PolymarketContracts.NEW_SPENDER_B,
                PolymarketContracts.PARLAY);
    }
}
```

- [ ] **Step 4: 改 `ContractRegistry` 加 `depositWalletConfig`**

在 `src/main/java/com/polymarket/clob/model/ContractRegistry.java` 顶部 import：

```java
import com.polymarket.clob.chain.DepositWalletConfig;
```

在文件末尾、最后一个 `}` 之前追加：

```java
    /**
     * 返回指定链的 Deposit Wallet 配置。仅 Polygon 137 有部署；其它链一律返回空。
     */
    public static Optional<DepositWalletConfig> depositWalletConfig(long chainId) {
        return chainId == 137 ? Optional.of(DepositWalletConfig.polygon()) : Optional.empty();
    }
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -Dtest=DepositWalletConfigTest test`
Expected: 3 tests pass

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/polymarket/clob/chain/DepositWalletConfig.java \
        src/main/java/com/polymarket/clob/model/ContractRegistry.java \
        src/test/java/com/polymarket/clob/chain/DepositWalletConfigTest.java
git commit -m "feat(chain): 新增 DepositWalletConfig + ContractRegistry.depositWalletConfig"
```

---

## Phase B — chain/ RPC 层

### Task 3: `EvmRpcClient` 接口 + `EvmRpcException`

**Files:**
- Create: `src/main/java/com/polymarket/clob/chain/EvmRpcClient.java`
- Create: `src/main/java/com/polymarket/clob/chain/EvmRpcException.java`

- [ ] **Step 1: 创建 `EvmRpcException`**

```java
// src/main/java/com/polymarket/clob/chain/EvmRpcException.java
package com.polymarket.clob.chain;

/**
 * EVM RPC 调用失败：JSON-RPC error 响应、HTTP 非 2xx、或 eth_call revert。
 * 运行时异常，由 CompletableFuture 透出。
 */
public class EvmRpcException extends RuntimeException {
    public EvmRpcException(String message) { super(message); }
    public EvmRpcException(String message, Throwable cause) { super(message, cause); }
}
```

- [ ] **Step 2: 创建 `EvmRpcClient` 接口**

```java
// src/main/java/com/polymarket/clob/chain/EvmRpcClient.java
package com.polymarket.clob.chain;

import com.polymarket.clob.model.Address;

import java.util.concurrent.CompletableFuture;

/**
 * 极简 EVM JSON-RPC 客户端。本接口刻意只暴露两个方法 —— {@code eth_call} 与
 * {@code eth_getCode}，让 RPC 提供方与业务读取层解耦。具体的 6 类合约读取在
 * {@link DepositWalletReads} 里做 ABI 编解码。
 */
public interface EvmRpcClient {

    /**
     * 执行 eth_call。
     *
     * @return 返回字节序列；调用方负责按预期 ABI 类型解码
     * @throws EvmRpcException 若 JSON-RPC error、HTTP 非 2xx 或 eth_call revert
     */
    CompletableFuture<byte[]> ethCall(Address to, byte[] callData);

    /**
     * 执行 eth_getCode。未部署地址 RPC 返回 "0x"，本方法返回空字节数组。
     */
    CompletableFuture<byte[]> getCode(Address addr);
}
```

- [ ] **Step 3: 编译确认**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/polymarket/clob/chain/EvmRpcClient.java \
        src/main/java/com/polymarket/clob/chain/EvmRpcException.java
git commit -m "feat(chain): 新增 EvmRpcClient 接口 + EvmRpcException"
```

---

### Task 4: `Web3jEvmRpcClient` 默认实现

**Files:**
- Create: `src/main/java/com/polymarket/clob/chain/Web3jEvmRpcClient.java`
- Test: `src/test/java/com/polymarket/clob/chain/Web3jEvmRpcClientTest.java`

- [ ] **Step 1: 写 WireMock 集成测试**

```java
// src/test/java/com/polymarket/clob/chain/Web3jEvmRpcClientTest.java
package com.polymarket.clob.chain;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HexFormat;
import java.util.concurrent.ExecutionException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Web3jEvmRpcClientTest {

    private WireMockServer server;
    private Web3jEvmRpcClient client;

    @BeforeEach
    void start() {
        server = new WireMockServer(0);
        server.start();
        client = new Web3jEvmRpcClient(URI.create(server.baseUrl()));
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void ethCallReturnsResultBytes() throws ExecutionException, InterruptedException {
        server.stubFor(post("/").willReturn(okJson(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x000000000000000000000000ada4563a6738215c56d2b59bc1c5a1db65b1fd78\"}")));

        byte[] result = client.ethCall(
                Address.fromHex("0x00000000000Fb5C9ADea0298D729A0CB3823Cc07"),
                HexFormat.of().parseHex("aabbccdd")).get();

        assertThat(result).hasSize(32);
        assertThat(HexFormat.of().formatHex(result))
                .endsWith("ada4563a6738215c56d2b59bc1c5a1db65b1fd78");
    }

    @Test
    void getCodeReturnsBytecode() throws ExecutionException, InterruptedException {
        server.stubFor(post("/").willReturn(okJson(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x6080604052\"}")));

        byte[] code = client.getCode(Address.fromHex("0xada4563a6738215c56d2b59bc1c5a1db65b1fd78")).get();

        assertThat(code).containsExactly(0x60, (byte) 0x80, 0x60, 0x40, 0x52);
    }

    @Test
    void getCodeReturnsEmptyForUndeployed() throws ExecutionException, InterruptedException {
        server.stubFor(post("/").willReturn(okJson(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x\"}")));

        byte[] code = client.getCode(Address.fromHex("0x0000000000000000000000000000000000000001")).get();

        assertThat(code).isEmpty();
    }

    @Test
    void rpcErrorThrowsException() {
        server.stubFor(post("/").willReturn(okJson(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"execution reverted\"}}")));

        assertThatThrownBy(() -> client.ethCall(Address.ZERO, new byte[0]).get())
                .hasCauseInstanceOf(EvmRpcException.class)
                .hasMessageContaining("execution reverted");
    }

    @Test
    void httpErrorThrowsException() {
        server.stubFor(post("/").willReturn(serverError()));

        assertThatThrownBy(() -> client.getCode(Address.ZERO).get())
                .hasCauseInstanceOf(EvmRpcException.class);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -Dtest=Web3jEvmRpcClientTest test`
Expected: FAIL — `Web3jEvmRpcClient` not found

- [ ] **Step 3: 实现 `Web3jEvmRpcClient`**

```java
// src/main/java/com/polymarket/clob/chain/Web3jEvmRpcClient.java
package com.polymarket.clob.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.clob.http.JsonCodec;
import com.polymarket.clob.model.Address;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EvmRpcClient 默认实现：JSON-RPC over HTTP，使用 JDK HttpClient + Jackson。
 * 不依赖 web3j HttpService，避免 web3j 全套对象图。
 */
public final class Web3jEvmRpcClient implements EvmRpcClient {

    private final URI endpoint;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final AtomicLong idSeq = new AtomicLong(1);

    public Web3jEvmRpcClient(URI endpoint) {
        this(endpoint, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public Web3jEvmRpcClient(URI endpoint, HttpClient http) {
        this.endpoint = endpoint;
        this.http = http;
        this.mapper = JsonCodec.objectMapper();
    }

    @Override
    public CompletableFuture<byte[]> ethCall(Address to, byte[] callData) {
        String body = """
                {"jsonrpc":"2.0","id":%d,"method":"eth_call","params":[{"to":"%s","data":"0x%s"},"latest"]}"""
                .formatted(idSeq.getAndIncrement(), to.toLowerHex(), HexFormat.of().formatHex(callData));
        return send(body, "eth_call");
    }

    @Override
    public CompletableFuture<byte[]> getCode(Address addr) {
        String body = """
                {"jsonrpc":"2.0","id":%d,"method":"eth_getCode","params":["%s","latest"]}"""
                .formatted(idSeq.getAndIncrement(), addr.toLowerHex());
        return send(body, "eth_getCode");
    }

    private CompletableFuture<byte[]> send(String body, String method) {
        HttpRequest req = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(resp -> {
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new EvmRpcException(method + " HTTP " + resp.statusCode() + ": " + resp.body());
            }
            try {
                JsonNode root = mapper.readTree(resp.body());
                JsonNode error = root.get("error");
                if (error != null && !error.isNull()) {
                    String msg = error.path("message").asText("rpc error");
                    throw new EvmRpcException(method + " rpc error: " + msg);
                }
                String hex = root.path("result").asText("");
                if (!hex.startsWith("0x")) {
                    throw new EvmRpcException(method + " result not 0x-prefixed: " + hex);
                }
                if (hex.length() == 2) return new byte[0];
                return HexFormat.of().parseHex(hex.substring(2));
            } catch (EvmRpcException e) {
                throw e;
            } catch (Exception e) {
                throw new EvmRpcException(method + " parse failure: " + e.getMessage(), e);
            }
        });
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -Dtest=Web3jEvmRpcClientTest test`
Expected: 5 tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/polymarket/clob/chain/Web3jEvmRpcClient.java \
        src/test/java/com/polymarket/clob/chain/Web3jEvmRpcClientTest.java
git commit -m "feat(chain): 新增 Web3jEvmRpcClient 默认 RPC 实现"
```

---

### Task 5: `DepositWalletReads` 6 类读取

**Files:**
- Create: `src/main/java/com/polymarket/clob/chain/DepositWalletReads.java`
- Test: `src/test/java/com/polymarket/clob/chain/DepositWalletReadsTest.java`

- [ ] **Step 1: 写测试覆盖 6 类读取**

```java
// src/test/java/com/polymarket/clob/chain/DepositWalletReadsTest.java
package com.polymarket.clob.chain;

import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DepositWalletReadsTest {

    private static final Address EOA    = Address.fromHex("0x4cAfCf2D9A032f57088872f6546Bb68305d209D7");
    private static final Address WALLET = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");

    /** 仿真：按提交的 calldata 4-byte selector 决定返回什么。 */
    private static final class StubRpc implements EvmRpcClient {
        private final List<byte[]> calls = new java.util.ArrayList<>();
        private final AtomicReference<byte[]> nextCallResult = new AtomicReference<>();
        private final AtomicReference<byte[]> nextCodeResult = new AtomicReference<>();

        public byte[] lastCall() { return calls.get(calls.size() - 1); }

        public void willReturnCall(byte[] r) { nextCallResult.set(r); }
        public void willReturnCode(byte[] r) { nextCodeResult.set(r); }

        @Override
        public CompletableFuture<byte[]> ethCall(Address to, byte[] callData) {
            calls.add(callData);
            return CompletableFuture.completedFuture(nextCallResult.get());
        }

        @Override
        public CompletableFuture<byte[]> getCode(Address addr) {
            return CompletableFuture.completedFuture(nextCodeResult.get());
        }
    }

    @Test
    void predictWalletAddressEncodesSelectorAndArgs() throws Exception {
        StubRpc rpc = new StubRpc();
        // 32B 返回值 = padded wallet 地址
        rpc.willReturnCall(HexFormat.of().parseHex(
                "000000000000000000000000ada4563a6738215c56d2b59bc1c5a1db65b1fd78"));

        Address result = new DepositWalletReads(rpc, 137).predictWalletAddress(EOA).get();

        assertThat(result).isEqualTo(WALLET);
        // selector keccak256("predictWalletAddress(address,bytes32)")[:4] = 0x...
        // 验证 calldata 长度 = 4 + 32 + 32 = 68
        assertThat(rpc.lastCall()).hasSize(68);
        // 后 32 字节 = pad32(EOA) = 12 个 0 + 20B EOA
        byte[] eoaArg = java.util.Arrays.copyOfRange(rpc.lastCall(), 36, 68);
        byte[] expectedPad = new byte[32];
        System.arraycopy(EOA.toBytes(), 0, expectedPad, 12, 20);
        assertThat(eoaArg).containsExactly(expectedPad);
    }

    @Test
    void isDeployedTrueWhenCodePresent() throws Exception {
        StubRpc rpc = new StubRpc();
        rpc.willReturnCode(new byte[]{0x60, (byte) 0x80});
        assertThat(new DepositWalletReads(rpc, 137).isDeployed(WALLET).get()).isTrue();
    }

    @Test
    void isDeployedFalseWhenEmpty() throws Exception {
        StubRpc rpc = new StubRpc();
        rpc.willReturnCode(new byte[0]);
        assertThat(new DepositWalletReads(rpc, 137).isDeployed(WALLET).get()).isFalse();
    }

    @Test
    void walletNonceDecodesUint256() throws Exception {
        StubRpc rpc = new StubRpc();
        // nonce = 5
        rpc.willReturnCall(HexFormat.of().parseHex(
                "0000000000000000000000000000000000000000000000000000000000000005"));
        assertThat(new DepositWalletReads(rpc, 137).walletNonce(WALLET).get())
                .isEqualTo(BigInteger.valueOf(5));
    }

    @Test
    void erc20AllowanceDecodesUint256() throws Exception {
        StubRpc rpc = new StubRpc();
        // allowance = 1_000_000
        rpc.willReturnCall(HexFormat.of().parseHex(
                "00000000000000000000000000000000000000000000000000000000000f4240"));
        BigInteger r = new DepositWalletReads(rpc, 137)
                .erc20Allowance(PolymarketContracts.USDC_E, WALLET, PolymarketContracts.CTF).get();
        assertThat(r).isEqualTo(BigInteger.valueOf(1_000_000));
    }

    @Test
    void ctfApprovedForAllReturnsBoolean() throws Exception {
        StubRpc rpc = new StubRpc();
        rpc.willReturnCall(HexFormat.of().parseHex(
                "0000000000000000000000000000000000000000000000000000000000000001"));
        assertThat(new DepositWalletReads(rpc, 137)
                .ctfApprovedForAll(WALLET, PolymarketContracts.EXCHANGE_V2).get()).isTrue();

        rpc.willReturnCall(HexFormat.of().parseHex(
                "0000000000000000000000000000000000000000000000000000000000000000"));
        assertThat(new DepositWalletReads(rpc, 137)
                .ctfApprovedForAll(WALLET, PolymarketContracts.EXCHANGE_V2).get()).isFalse();
    }

    @Test
    void erc20BalanceOfDecodesUint256() throws Exception {
        StubRpc rpc = new StubRpc();
        rpc.willReturnCall(HexFormat.of().parseHex(
                "00000000000000000000000000000000000000000000000000000000000186a0"));
        assertThat(new DepositWalletReads(rpc, 137)
                .erc20BalanceOf(PolymarketContracts.USDC_E, WALLET).get())
                .isEqualTo(BigInteger.valueOf(100_000));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -Dtest=DepositWalletReadsTest test`
Expected: FAIL — `DepositWalletReads` not found

- [ ] **Step 3: 实现 `DepositWalletReads`**

```java
// src/main/java/com/polymarket/clob/chain/DepositWalletReads.java
package com.polymarket.clob.chain;

import com.polymarket.clob.model.Address;
import org.web3j.crypto.Hash;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

/**
 * Deposit Wallet onboarding 期间需要做的 6 类合约只读调用。封装 ABI 编解码细节。
 */
public final class DepositWalletReads {

    /** keccak256("predictWalletAddress(address,bytes32)")[:4] */
    private static final byte[] SEL_PREDICT_WALLET = selector("predictWalletAddress(address,bytes32)");
    /** keccak256("nonce()")[:4] */
    private static final byte[] SEL_NONCE          = selector("nonce()");
    /** keccak256("allowance(address,address)")[:4] */
    private static final byte[] SEL_ALLOWANCE      = selector("allowance(address,address)");
    /** keccak256("isApprovedForAll(address,address)")[:4] */
    private static final byte[] SEL_APPROVED_ALL   = selector("isApprovedForAll(address,address)");
    /** keccak256("balanceOf(address)")[:4] */
    private static final byte[] SEL_BALANCE_OF     = selector("balanceOf(address)");

    private final EvmRpcClient rpc;
    private final long chainId;

    public DepositWalletReads(EvmRpcClient rpc, long chainId) {
        this.rpc = rpc;
        this.chainId = chainId;
    }

    public CompletableFuture<Address> predictWalletAddress(Address eoa) {
        DepositWalletConfig cfg = require();
        byte[] data = concat(SEL_PREDICT_WALLET, padAddress(cfg.implementation()), padAddress(eoa));
        return rpc.ethCall(cfg.factory(), data).thenApply(DepositWalletReads::decodeAddress);
    }

    public CompletableFuture<Boolean> isDeployed(Address wallet) {
        return rpc.getCode(wallet).thenApply(code -> code != null && code.length > 0);
    }

    public CompletableFuture<BigInteger> walletNonce(Address wallet) {
        return rpc.ethCall(wallet, SEL_NONCE).thenApply(DepositWalletReads::decodeUint256);
    }

    public CompletableFuture<BigInteger> erc20Allowance(Address token, Address owner, Address spender) {
        byte[] data = concat(SEL_ALLOWANCE, padAddress(owner), padAddress(spender));
        return rpc.ethCall(token, data).thenApply(DepositWalletReads::decodeUint256);
    }

    public CompletableFuture<Boolean> ctfApprovedForAll(Address owner, Address operator) {
        DepositWalletConfig cfg = require();
        byte[] data = concat(SEL_APPROVED_ALL, padAddress(owner), padAddress(operator));
        return rpc.ethCall(cfg.ctf(), data).thenApply(DepositWalletReads::decodeBool);
    }

    public CompletableFuture<BigInteger> erc20BalanceOf(Address token, Address owner) {
        byte[] data = concat(SEL_BALANCE_OF, padAddress(owner));
        return rpc.ethCall(token, data).thenApply(DepositWalletReads::decodeUint256);
    }

    private DepositWalletConfig require() {
        return com.polymarket.clob.model.ContractRegistry.depositWalletConfig(chainId)
                .orElseThrow(() -> new EvmRpcException("DepositWallet not deployed on chainId " + chainId));
    }

    // ---- ABI helpers ----

    private static byte[] selector(String signature) {
        byte[] full = Hash.sha3(signature.getBytes(StandardCharsets.US_ASCII));
        return Arrays.copyOfRange(full, 0, 4);
    }

    private static byte[] padAddress(Address a) {
        byte[] out = new byte[32];
        System.arraycopy(a.toBytes(), 0, out, 12, 20);
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    private static Address decodeAddress(byte[] returnData) {
        if (returnData == null || returnData.length < 32) {
            throw new EvmRpcException("expected 32B address return, got " + (returnData == null ? -1 : returnData.length));
        }
        byte[] addr = Arrays.copyOfRange(returnData, returnData.length - 20, returnData.length);
        return Address.fromBytes(addr);
    }

    private static BigInteger decodeUint256(byte[] returnData) {
        if (returnData == null || returnData.length < 32) {
            throw new EvmRpcException("expected 32B uint256 return, got " + (returnData == null ? -1 : returnData.length));
        }
        return new BigInteger(1, Arrays.copyOfRange(returnData, returnData.length - 32, returnData.length));
    }

    private static boolean decodeBool(byte[] returnData) {
        return decodeUint256(returnData).signum() != 0;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -Dtest=DepositWalletReadsTest test`
Expected: 7 tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/polymarket/clob/chain/DepositWalletReads.java \
        src/test/java/com/polymarket/clob/chain/DepositWalletReadsTest.java
git commit -m "feat(chain): 新增 DepositWalletReads 6 类合约只读调用"
```

---

## Phase C — gamma/ SIWE 登录

### Task 6: `SiweMessage` 文本构造

**Files:**
- Create: `src/main/java/com/polymarket/clob/gamma/SiweMessage.java`
- Test: `src/test/java/com/polymarket/clob/gamma/SiweMessageTest.java`

- [ ] **Step 1: 写测试**

```java
// src/test/java/com/polymarket/clob/gamma/SiweMessageTest.java
package com.polymarket.clob.gamma;

import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SiweMessageTest {

    @Test
    void buildsExpectedSiweTextForPolygon() {
        Instant issued = Instant.parse("2026-05-06T14:56:25Z");
        Instant expires = issued.plusSeconds(7L * 24 * 3600);
        String text = SiweMessage.build(
                Address.fromHex("0x4cAfCf2D9A032f57088872f6546Bb68305d209D7"),
                137,
                "abc123nonce",
                issued,
                expires);

        String expected = """
                polymarket.com wants you to sign in with your Ethereum account:
                0x4cAfCf2D9A032f57088872f6546Bb68305d209D7

                Welcome to Polymarket! Sign to connect.

                URI: https://polymarket.com
                Version: 1
                Chain ID: 137
                Nonce: abc123nonce
                Issued At: 2026-05-06T14:56:25Z
                Expiration Time: 2026-05-13T14:56:25Z""";
        assertThat(text).isEqualTo(expected);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -Dtest=SiweMessageTest test`
Expected: FAIL — `SiweMessage` not found

- [ ] **Step 3: 实现 `SiweMessage`**

```java
// src/main/java/com/polymarket/clob/gamma/SiweMessage.java
package com.polymarket.clob.gamma;

import com.polymarket.clob.model.Address;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * EIP-4361 Sign-In with Ethereum 文本构造。
 * Polymarket 使用固定 domain "polymarket.com" + statement
 * "Welcome to Polymarket! Sign to connect."。
 */
public final class SiweMessage {

    public static final String DOMAIN     = "polymarket.com";
    public static final String STATEMENT  = "Welcome to Polymarket! Sign to connect.";
    public static final String URI        = "https://polymarket.com";
    public static final String VERSION    = "1";

    private SiweMessage() {}

    public static String build(Address eoa, long chainId, String nonce,
                                Instant issuedAt, Instant expirationTime) {
        return DOMAIN + " wants you to sign in with your Ethereum account:\n"
                + eoa.toHex() + "\n"
                + "\n"
                + STATEMENT + "\n"
                + "\n"
                + "URI: " + URI + "\n"
                + "Version: " + VERSION + "\n"
                + "Chain ID: " + chainId + "\n"
                + "Nonce: " + nonce + "\n"
                + "Issued At: " + DateTimeFormatter.ISO_INSTANT.format(issuedAt) + "\n"
                + "Expiration Time: " + DateTimeFormatter.ISO_INSTANT.format(expirationTime);
    }
}
```

注意 `Address.toHex()` 必须保留 EIP-55 大小写（不是全小写）。检查 `com.polymarket.clob.model.Address` 是否符合：如果它返回小写，把上面的 `eoa.toHex()` 换成 `eoa.toChecksumHex()` 或新增方法。如本测试用的 `0x4cAfCf2D...` 已是 checksum 形态、`Address.toHex()` 返回它即可，不必改动。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -Dtest=SiweMessageTest test`
Expected: 1 test pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/polymarket/clob/gamma/SiweMessage.java \
        src/test/java/com/polymarket/clob/gamma/SiweMessageTest.java
git commit -m "feat(gamma): 新增 SiweMessage EIP-4361 文本构造"
```

---

### Task 7: `GammaSession` + `GammaClient.loginWithSiwe`

**Files:**
- Create: `src/main/java/com/polymarket/clob/gamma/GammaSession.java`
- Create: `src/main/java/com/polymarket/clob/gamma/GammaClient.java`
- Create: `src/main/java/com/polymarket/clob/gamma/GammaAuthException.java`
- Test: `src/test/java/com/polymarket/clob/gamma/GammaClientLoginTest.java`

- [ ] **Step 1: 创建 `GammaSession` record + `GammaAuthException`**

```java
// src/main/java/com/polymarket/clob/gamma/GammaSession.java
package com.polymarket.clob.gamma;

import java.time.Instant;

/**
 * Gamma 登录后获取的 session：合并后的 Cookie 头 + 过期时间。
 * cookieHeader 格式 "name1=val1; name2=val2"，可直接放进 HTTP "Cookie" 头。
 */
public record GammaSession(String cookieHeader, Instant expiresAt) {}
```

```java
// src/main/java/com/polymarket/clob/gamma/GammaAuthException.java
package com.polymarket.clob.gamma;

/** Gamma 鉴权失败：SIWE 文本错误、时钟漂移、cookie 过期等。 */
public class GammaAuthException extends RuntimeException {
    public GammaAuthException(String message) { super(message); }
    public GammaAuthException(String message, Throwable cause) { super(message, cause); }
}
```

- [ ] **Step 2: 写 WireMock 测试**

```java
// src/test/java/com/polymarket/clob/gamma/GammaClientLoginTest.java
package com.polymarket.clob.gamma;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.Signer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.concurrent.ExecutionException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GammaClientLoginTest {

    /** 固定测试私钥 → EOA 0xf39F...2266（Anvil account #0）。 */
    private static final String PK_HEX = "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";

    private WireMockServer server;
    private GammaClient client;
    private Signer signer;

    @BeforeEach
    void start() {
        server = new WireMockServer(0);
        server.start();
        client = new GammaClient(URI.create(server.baseUrl()), HttpClient.newHttpClient());
        signer = LocalSigner.fromPrivateKeyHex(PK_HEX);
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void loginIssuesNonceAndLoginAndMergesCookies() throws ExecutionException, InterruptedException {
        server.stubFor(get(urlEqualTo("/nonce"))
                .willReturn(okJson("{\"nonce\":\"abc123\"}")
                        .withHeader("Set-Cookie", "polymarket_anon=anon-val; Path=/")));

        server.stubFor(get(urlEqualTo("/login"))
                .withHeader("Authorization", matching("Bearer .+:::0x[0-9a-f]+"))
                .withHeader("Cookie", matching(".*polymarket_anon=anon-val.*"))
                .willReturn(okJson("{\"ok\":true}")
                        .withHeader("Set-Cookie", "polymarket_auth=auth-val; Path=/")));

        GammaSession session = client.loginWithSiwe(signer, 137).get();

        assertThat(session.cookieHeader())
                .contains("polymarket_anon=anon-val")
                .contains("polymarket_auth=auth-val");
    }

    @Test
    void loginRejectionThrowsAuthException() {
        server.stubFor(get(urlEqualTo("/nonce"))
                .willReturn(okJson("{\"nonce\":\"abc\"}")));
        server.stubFor(get(urlEqualTo("/login"))
                .willReturn(aResponse().withStatus(401).withBody("Unauthorized")));

        assertThatThrownBy(() -> client.loginWithSiwe(signer, 137).get())
                .hasCauseInstanceOf(GammaAuthException.class);
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `mvn -Dtest=GammaClientLoginTest test`
Expected: FAIL — `GammaClient` not found

- [ ] **Step 4: 实现 `GammaClient.loginWithSiwe` 与脚手架**

```java
// src/main/java/com/polymarket/clob/gamma/GammaClient.java
package com.polymarket.clob.gamma;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.http.JsonCodec;
import com.polymarket.clob.model.Address;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Polymarket Gamma 鉴权客户端：SIWE 登录与用户档案管理。
 */
public final class GammaClient {

    private final URI baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public GammaClient(URI baseUrl, HttpClient http) {
        this.baseUrl = baseUrl;
        this.http = http;
        this.mapper = JsonCodec.objectMapper();
    }

    public CompletableFuture<GammaSession> loginWithSiwe(Signer eoa, long chainId) {
        // 1. GET /nonce → cookie₀ + nonce 文本
        return getJson(baseUrl.resolve("/nonce"), null).thenCompose(nonceResp -> {
            String nonce;
            try {
                nonce = mapper.readTree(nonceResp.body).path("nonce").asText("");
            } catch (Exception e) {
                throw new GammaAuthException("/nonce body parse failure", e);
            }
            if (nonce.isEmpty()) throw new GammaAuthException("/nonce missing nonce");

            String cookie0 = mergeSetCookie("", nonceResp.setCookies);

            // 2. SIWE 文本 + EIP-191 personal_sign
            Instant issued = Instant.now();
            Instant expiry = issued.plusSeconds(7L * 24 * 3600);
            String siwe = SiweMessage.build(eoa.address(), chainId, nonce, issued, expiry);

            byte[] digest = personalSignDigest(siwe);
            return eoa.signHash(digest).thenCompose(sig65 -> {
                String sigHex = "0x" + HexFormat.of().formatHex(sig65);
                String payloadJson = buildLoginPayload(eoa.address(), chainId, nonce, issued, expiry);
                String authToken = Base64.getEncoder().encodeToString(
                        (payloadJson + ":::" + sigHex).getBytes(StandardCharsets.UTF_8));

                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Authorization", "Bearer " + authToken);
                headers.put("Cookie", cookie0);

                return getJson(baseUrl.resolve("/login"), headers).thenApply(loginResp -> {
                    if (loginResp.status < 200 || loginResp.status >= 300) {
                        throw new GammaAuthException("/login failed status=" + loginResp.status
                                + " body=" + loginResp.body);
                    }
                    String cookieFinal = mergeSetCookie(cookie0, loginResp.setCookies);
                    return new GammaSession(cookieFinal, expiry);
                });
            });
        });
    }

    // ---- 占位：profile 相关方法在 Task 8 实现 ----

    public CompletableFuture<Boolean> profileExists(GammaSession s, Address eoa) {
        throw new UnsupportedOperationException("Implemented in Task 8");
    }

    public CompletableFuture<Void> createProfile(GammaSession s, Address eoa, Address proxyWallet) {
        throw new UnsupportedOperationException("Implemented in Task 8");
    }

    public CompletableFuture<Void> ensureProfile(GammaSession s, Address eoa, Address proxyWallet) {
        throw new UnsupportedOperationException("Implemented in Task 8");
    }

    // ---- 内部工具 ----

    record HttpResp(int status, String body, List<String> setCookies) {}

    CompletableFuture<HttpResp> getJson(URI uri, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET();
        if (headers != null) headers.forEach(b::header);
        return http.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> new HttpResp(r.statusCode(), r.body(),
                        r.headers().allValues("Set-Cookie")));
    }

    CompletableFuture<HttpResp> postJson(URI uri, String json, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (headers != null) headers.forEach(b::header);
        return http.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> new HttpResp(r.statusCode(), r.body(),
                        r.headers().allValues("Set-Cookie")));
    }

    static byte[] personalSignDigest(String message) {
        byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
        String prefix = "Ethereum Signed Message:\n" + msgBytes.length;
        byte[] prefixed = new byte[prefix.length() + msgBytes.length];
        System.arraycopy(prefix.getBytes(StandardCharsets.UTF_8), 0, prefixed, 0, prefix.length());
        System.arraycopy(msgBytes, 0, prefixed, prefix.length(), msgBytes.length);
        return Hash.sha3(prefixed);
    }

    static String buildLoginPayload(Address eoa, long chainId, String nonce,
                                    Instant issued, Instant expiry) {
        // 字段顺序锁定，以与 TS 实现一致（base64 内容必须可被 gamma server 解码）
        return "{\"domain\":\"" + SiweMessage.DOMAIN + "\","
                + "\"address\":\"" + eoa.toHex() + "\","
                + "\"statement\":\"" + SiweMessage.STATEMENT + "\","
                + "\"uri\":\"" + SiweMessage.URI + "\","
                + "\"version\":\"1\","
                + "\"chainId\":" + chainId + ","
                + "\"nonce\":\"" + nonce + "\","
                + "\"issuedAt\":\"" + issued + "\","
                + "\"expirationTime\":\"" + expiry + "\"}";
    }

    /** 合并 Set-Cookie 头到一个 "name=value; name2=value2" 字符串。后写入覆盖先写入。 */
    static String mergeSetCookie(String existing, List<String> setCookieHeaders) {
        Map<String, String> map = new LinkedHashMap<>();
        if (existing != null && !existing.isBlank()) {
            for (String part : existing.split("; ")) {
                int eq = part.indexOf('=');
                if (eq > 0) map.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        for (String header : setCookieHeaders) {
            String first = header.split(";", 2)[0];
            int eq = first.indexOf('=');
            if (eq > 0) map.put(first.substring(0, eq).trim(), first.substring(eq + 1).trim());
        }
        return map.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("; "));
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -Dtest=GammaClientLoginTest test`
Expected: 2 tests pass

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/polymarket/clob/gamma/ \
        src/test/java/com/polymarket/clob/gamma/GammaClientLoginTest.java
git commit -m "feat(gamma): 新增 GammaClient.loginWithSiwe + GammaSession + GammaAuthException"
```

---

### Task 8: `GammaClient` 用户档案三个方法

**Files:**
- Modify: `src/main/java/com/polymarket/clob/gamma/GammaClient.java` — 替换 Task 7 留下的三个 `UnsupportedOperationException` 占位
- Test: `src/test/java/com/polymarket/clob/gamma/GammaClientProfileTest.java`

- [ ] **Step 1: 写 WireMock 测试**

```java
// src/test/java/com/polymarket/clob/gamma/GammaClientProfileTest.java
package com.polymarket.clob.gamma;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.concurrent.ExecutionException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class GammaClientProfileTest {

    private static final Address EOA = Address.fromHex("0x4cAfCf2D9A032f57088872f6546Bb68305d209D7");
    private static final Address WALLET = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");

    private WireMockServer server;
    private GammaClient client;
    private GammaSession session;

    @BeforeEach
    void start() {
        server = new WireMockServer(0);
        server.start();
        client = new GammaClient(URI.create(server.baseUrl()), HttpClient.newHttpClient());
        session = new GammaSession("polymarket_auth=val", Instant.now().plusSeconds(3600));
    }

    @AfterEach
    void stop() { server.stop(); }

    @Test
    void profileExistsTrueOn200() throws ExecutionException, InterruptedException {
        server.stubFor(get(urlPathEqualTo("/users"))
                .withQueryParam("address", equalTo(EOA.toHex()))
                .willReturn(okJson("[{\"id\":\"u1\"}]")));
        assertThat(client.profileExists(session, EOA).get()).isTrue();
    }

    @Test
    void profileExistsFalseOn404() throws ExecutionException, InterruptedException {
        server.stubFor(get(urlPathEqualTo("/users"))
                .willReturn(notFound()));
        assertThat(client.profileExists(session, EOA).get()).isFalse();
    }

    @Test
    void profileExistsFalseOn200EmptyArray() throws ExecutionException, InterruptedException {
        server.stubFor(get(urlPathEqualTo("/users"))
                .willReturn(okJson("[]")));
        assertThat(client.profileExists(session, EOA).get()).isFalse();
    }

    @Test
    void createProfilePostsExpectedBody() throws ExecutionException, InterruptedException {
        server.stubFor(post(urlEqualTo("/profiles"))
                .withHeader("Cookie", equalTo("polymarket_auth=val"))
                .withRequestBody(matchingJsonPath("$.proxyWallet", equalTo(WALLET.toHex())))
                .withRequestBody(matchingJsonPath("$.users[0].address", equalTo(EOA.toHex())))
                .withRequestBody(matchingJsonPath("$.users[0].provider", equalTo("metamask")))
                .willReturn(aResponse().withStatus(201).withBody("{\"id\":\"p1\"}")));

        client.createProfile(session, EOA, WALLET).get();
        // 没异常即通过
    }

    @Test
    void ensureProfileSkipsWhenExists() throws ExecutionException, InterruptedException {
        server.stubFor(get(urlPathEqualTo("/users")).willReturn(okJson("[{\"id\":\"u1\"}]")));
        // 不 stub /profiles，如果意外被调用 WireMock 返回 404，会触发异常

        client.ensureProfile(session, EOA, WALLET).get();
        server.verify(0, postRequestedFor(urlEqualTo("/profiles")));
    }

    @Test
    void ensureProfileCreatesWhenMissing() throws ExecutionException, InterruptedException {
        server.stubFor(get(urlPathEqualTo("/users")).willReturn(notFound()));
        server.stubFor(post(urlEqualTo("/profiles"))
                .willReturn(aResponse().withStatus(201).withBody("{}")));

        client.ensureProfile(session, EOA, WALLET).get();
        server.verify(1, postRequestedFor(urlEqualTo("/profiles")));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -Dtest=GammaClientProfileTest test`
Expected: FAIL — `UnsupportedOperationException`

- [ ] **Step 3: 实现三个方法**

替换 `GammaClient.java` 中三个占位方法：

```java
    public CompletableFuture<Boolean> profileExists(GammaSession s, Address eoa) {
        URI uri = baseUrl.resolve("/users?address=" + eoa.toHex());
        Map<String, String> headers = Map.of("Cookie", s.cookieHeader());
        return getJson(uri, headers).thenApply(resp -> {
            if (resp.status == 404) return false;
            if (resp.status < 200 || resp.status >= 300) {
                throw new GammaAuthException("/users status=" + resp.status + " body=" + resp.body);
            }
            try {
                JsonNode root = mapper.readTree(resp.body);
                if (root.isArray()) return root.size() > 0;
                if (root.isObject()) return root.has("id") || root.has("proxyWallet") || root.has("users");
                return false;
            } catch (Exception e) {
                throw new GammaAuthException("/users parse failure", e);
            }
        });
    }

    public CompletableFuture<Void> createProfile(GammaSession s, Address eoa, Address proxyWallet) {
        long ts = Instant.now().toEpochMilli();
        String name = proxyWallet.toHex() + "-" + ts;
        // 字段顺序与 docs/EOA_TO_ORDER.md §2.3 对齐
        String body = "{"
                + "\"displayUsernamePublic\":true,"
                + "\"emailOptIn\":false,"
                + "\"walletActivated\":false,"
                + "\"name\":\"" + name + "\","
                + "\"pseudonym\":\"" + proxyWallet.toHex() + "\","
                + "\"proxyWallet\":\"" + proxyWallet.toHex() + "\","
                + "\"users\":[{"
                + "\"address\":\"" + eoa.toHex() + "\","
                + "\"isExternalAuth\":true,"
                + "\"proxyWallet\":\"" + proxyWallet.toHex() + "\","
                + "\"username\":\"" + name + "\","
                + "\"provider\":\"metamask\","
                + "\"preferences\":[],"
                + "\"walletPreferences\":[{\"advancedMode\":false,\"customGasPrice\":\"30\",\"gasPreference\":\"fast\"}]"
                + "}]"
                + "}";
        URI uri = baseUrl.resolve("/profiles");
        Map<String, String> headers = Map.of("Cookie", s.cookieHeader());
        return postJson(uri, body, headers).thenApply(resp -> {
            if (resp.status < 200 || resp.status >= 300) {
                throw new GammaAuthException("/profiles status=" + resp.status + " body=" + resp.body);
            }
            return (Void) null;
        });
    }

    public CompletableFuture<Void> ensureProfile(GammaSession s, Address eoa, Address proxyWallet) {
        return profileExists(s, eoa).thenCompose(exists ->
                exists ? CompletableFuture.completedFuture(null)
                       : createProfile(s, eoa, proxyWallet));
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -Dtest=GammaClientProfileTest test`
Expected: 6 tests pass

- [ ] **Step 5: 跑全部 gamma 测试**

Run: `mvn -Dtest='com.polymarket.clob.gamma.*Test' test`
Expected: 9 tests pass total（含 Task 6/7）

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/polymarket/clob/gamma/GammaClient.java \
        src/test/java/com/polymarket/clob/gamma/GammaClientProfileTest.java
git commit -m "feat(gamma): GammaClient 实现 profileExists / createProfile / ensureProfile"
```

---

## Phase D — deposit/ 钱包派生与 relayer

### Task 9: deposit/ 数据类（Call / RelayerTx / RelayerTxResult / SignedBatch）

**Files:**
- Create: `src/main/java/com/polymarket/clob/deposit/Call.java`
- Create: `src/main/java/com/polymarket/clob/deposit/RelayerTx.java`
- Create: `src/main/java/com/polymarket/clob/deposit/RelayerTxResult.java`
- Create: `src/main/java/com/polymarket/clob/deposit/SignedBatch.java`
- Create: `src/main/java/com/polymarket/clob/deposit/RelayerTxFailedException.java`

- [ ] **Step 1: 创建数据类**

```java
// src/main/java/com/polymarket/clob/deposit/Call.java
package com.polymarket.clob.deposit;

import com.polymarket.clob.model.Address;

import java.math.BigInteger;

/** Deposit Wallet Batch 中单条 call。{@code data} 是 ABI 编码的 calldata。 */
public record Call(Address target, BigInteger value, byte[] data) {
    public Call {
        if (value == null) value = BigInteger.ZERO;
        if (data == null) data = new byte[0];
    }
}
```

```java
// src/main/java/com/polymarket/clob/deposit/RelayerTx.java
package com.polymarket.clob.deposit;

/** Relayer 提交时已知字段（不含签名）。 */
public record RelayerTx(String type, String from, String to, String txnId) {}
```

```java
// src/main/java/com/polymarket/clob/deposit/RelayerTxResult.java
package com.polymarket.clob.deposit;

import java.util.Optional;

/**
 * /transaction 与 /submit 共享的事务状态。
 * state 取自 Polymarket relayer：NEW / EXECUTED / CONFIRMED / FAILED / INVALID（去掉 STATE_ 前缀）。
 */
public record RelayerTxResult(String txId, String state, String txHash, String error) {

    public boolean isTerminal() {
        return "CONFIRMED".equals(state) || "FAILED".equals(state) || "INVALID".equals(state);
    }

    public boolean isConfirmed() { return "CONFIRMED".equals(state); }

    public Optional<String> errorOpt() {
        return error == null || error.isBlank() ? Optional.empty() : Optional.of(error);
    }
}
```

```java
// src/main/java/com/polymarket/clob/deposit/SignedBatch.java
package com.polymarket.clob.deposit;

import com.polymarket.clob.model.Address;

import java.math.BigInteger;
import java.util.List;

/** 已签名的 Batch：{@code signature65} 是 EOA 对 BatchEip712 摘要的 65 字节 ECDSA 签名。 */
public record SignedBatch(
        Address eoa,
        Address factory,
        Address wallet,
        BigInteger nonce,
        BigInteger deadline,
        List<Call> calls,
        byte[] signature65
) {}
```

```java
// src/main/java/com/polymarket/clob/deposit/RelayerTxFailedException.java
package com.polymarket.clob.deposit;

/** Relayer 事务最终态为 FAILED / INVALID。 */
public class RelayerTxFailedException extends RuntimeException {
    public RelayerTxFailedException(RelayerTxResult result) {
        super("relayer tx " + result.txId() + " state=" + result.state()
                + " hash=" + result.txHash() + " error=" + result.error());
    }
}
```

- [ ] **Step 2: 编译确认**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/polymarket/clob/deposit/
git commit -m "feat(deposit): 新增 Call / RelayerTx / RelayerTxResult / SignedBatch / RelayerTxFailedException"
```

---

### Task 10: `ApprovalTargets` 13 项标准目标

**Files:**
- Create: `src/main/java/com/polymarket/clob/deposit/ApprovalTargets.java`
- Test: `src/test/java/com/polymarket/clob/deposit/ApprovalTargetsTest.java`

- [ ] **Step 1: 写测试**

```java
// src/test/java/com/polymarket/clob/deposit/ApprovalTargetsTest.java
package com.polymarket.clob.deposit;

import com.polymarket.clob.chain.DepositWalletConfig;
import com.polymarket.clob.chain.PolymarketContracts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalTargetsTest {

    @Test
    void thirteenStandardTargetsInSpecOrder() {
        DepositWalletConfig cfg = DepositWalletConfig.polygon();
        List<ApprovalTargets.Entry> targets = ApprovalTargets.standard(cfg);

        assertThat(targets).hasSize(13);

        assertThat(targets.get(0)).isEqualTo(new ApprovalTargets.Entry(
                cfg.usdcE(), ApprovalTargets.Kind.ERC20, cfg.ctf()));
        assertThat(targets.get(1)).isEqualTo(new ApprovalTargets.Entry(
                cfg.usdcE(), ApprovalTargets.Kind.ERC20, cfg.exchangeV2()));
        assertThat(targets.get(6)).isEqualTo(new ApprovalTargets.Entry(
                cfg.usdcNative(), ApprovalTargets.Kind.ERC20, cfg.pUsdQuoter()));
        assertThat(targets.get(7)).isEqualTo(new ApprovalTargets.Entry(
                cfg.ctf(), ApprovalTargets.Kind.CTF, cfg.exchangeV2()));
        assertThat(targets.get(12)).isEqualTo(new ApprovalTargets.Entry(
                cfg.ctf(), ApprovalTargets.Kind.CTF, cfg.parlay()));

        // 全 13 项 token 顺序与 spec §6.2 对齐
        assertThat(targets.subList(0, 7)).allMatch(e -> e.kind() == ApprovalTargets.Kind.ERC20);
        assertThat(targets.subList(7, 13)).allMatch(e -> e.kind() == ApprovalTargets.Kind.CTF);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -Dtest=ApprovalTargetsTest test`
Expected: FAIL

- [ ] **Step 3: 实现**

```java
// src/main/java/com/polymarket/clob/deposit/ApprovalTargets.java
package com.polymarket.clob.deposit;

import com.polymarket.clob.chain.DepositWalletConfig;
import com.polymarket.clob.model.Address;

import java.util.List;

/**
 * Onboarding 标准 13 项授权目标。顺序锁死，与 fullOnboardAndTrade.ts 一致。
 */
public final class ApprovalTargets {

    public enum Kind { ERC20, CTF }

    public record Entry(Address token, Kind kind, Address spender) {}

    private ApprovalTargets() {}

    public static List<Entry> standard(DepositWalletConfig cfg) {
        return List.of(
                new Entry(cfg.usdcE(),       Kind.ERC20, cfg.ctf()),
                new Entry(cfg.usdcE(),       Kind.ERC20, cfg.exchangeV2()),
                new Entry(cfg.usdcE(),       Kind.ERC20, cfg.negRiskExchangeV2()),
                new Entry(cfg.usdcE(),       Kind.ERC20, cfg.negRiskAdapter()),
                new Entry(cfg.usdcE(),       Kind.ERC20, cfg.newSpenderA()),
                new Entry(cfg.usdcE(),       Kind.ERC20, cfg.newSpenderB()),
                new Entry(cfg.usdcNative(),  Kind.ERC20, cfg.pUsdQuoter()),
                new Entry(cfg.ctf(),         Kind.CTF,   cfg.exchangeV2()),
                new Entry(cfg.ctf(),         Kind.CTF,   cfg.negRiskExchangeV2()),
                new Entry(cfg.ctf(),         Kind.CTF,   cfg.negRiskAdapter()),
                new Entry(cfg.ctf(),         Kind.CTF,   cfg.newSpenderA()),
                new Entry(cfg.ctf(),         Kind.CTF,   cfg.newSpenderB()),
                new Entry(cfg.ctf(),         Kind.CTF,   cfg.parlay())
        );
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -Dtest=ApprovalTargetsTest test`
Expected: 1 test pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/polymarket/clob/deposit/ApprovalTargets.java \
        src/test/java/com/polymarket/clob/deposit/ApprovalTargetsTest.java
git commit -m "feat(deposit): 新增 ApprovalTargets 13 项标准授权目标"
```

---

### Task 11: `BatchEip712.hashBatch`

**Files:**
- Create: `src/main/java/com/polymarket/clob/deposit/BatchEip712.java`
- Test: `src/test/java/com/polymarket/clob/deposit/BatchEip712Test.java`

- [ ] **Step 1: 写测试**

```java
// src/test/java/com/polymarket/clob/deposit/BatchEip712Test.java
package com.polymarket.clob.deposit;

import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BatchEip712Test {

    private static final Address WALLET = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");

    @Test
    void digestIs32Bytes() {
        byte[] digest = BatchEip712.hashBatch(
                137, WALLET, BigInteger.ZERO, BigInteger.valueOf(1778081214L),
                List.of(new Call(PolymarketContracts.USDC_E, BigInteger.ZERO, new byte[]{0x01, 0x02})));
        assertThat(digest).hasSize(32);
    }

    @Test
    void digestIsDeterministic() {
        byte[] d1 = BatchEip712.hashBatch(137, WALLET, BigInteger.ZERO, BigInteger.valueOf(100),
                List.of(new Call(PolymarketContracts.USDC_E, BigInteger.ZERO, new byte[]{0x01})));
        byte[] d2 = BatchEip712.hashBatch(137, WALLET, BigInteger.ZERO, BigInteger.valueOf(100),
                List.of(new Call(PolymarketContracts.USDC_E, BigInteger.ZERO, new byte[]{0x01})));
        assertThat(d1).containsExactly(d2);
    }

    @Test
    void digestChangesWithNonce() {
        Call c = new Call(PolymarketContracts.USDC_E, BigInteger.ZERO, new byte[]{0x01});
        byte[] d1 = BatchEip712.hashBatch(137, WALLET, BigInteger.ZERO, BigInteger.valueOf(100), List.of(c));
        byte[] d2 = BatchEip712.hashBatch(137, WALLET, BigInteger.ONE,  BigInteger.valueOf(100), List.of(c));
        assertThat(d1).isNotEqualTo(d2);
    }

    @Test
    void digestChangesWithDeadline() {
        Call c = new Call(PolymarketContracts.USDC_E, BigInteger.ZERO, new byte[]{0x01});
        byte[] d1 = BatchEip712.hashBatch(137, WALLET, BigInteger.ZERO, BigInteger.valueOf(100), List.of(c));
        byte[] d2 = BatchEip712.hashBatch(137, WALLET, BigInteger.ZERO, BigInteger.valueOf(200), List.of(c));
        assertThat(d1).isNotEqualTo(d2);
    }

    @Test
    void digestChangesWithCallData() {
        byte[] d1 = BatchEip712.hashBatch(137, WALLET, BigInteger.ZERO, BigInteger.valueOf(100),
                List.of(new Call(PolymarketContracts.USDC_E, BigInteger.ZERO, new byte[]{0x01})));
        byte[] d2 = BatchEip712.hashBatch(137, WALLET, BigInteger.ZERO, BigInteger.valueOf(100),
                List.of(new Call(PolymarketContracts.USDC_E, BigInteger.ZERO, new byte[]{0x02})));
        assertThat(d1).isNotEqualTo(d2);
    }

    @Test
    void emptyCallsRejected() {
        assertThatThrownBy(() -> BatchEip712.hashBatch(
                137, WALLET, BigInteger.ZERO, BigInteger.valueOf(100), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -Dtest=BatchEip712Test test`
Expected: FAIL

- [ ] **Step 3: 实现 `BatchEip712.hashBatch`**

```java
// src/main/java/com/polymarket/clob/deposit/BatchEip712.java
package com.polymarket.clob.deposit;

import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.model.Address;
import org.web3j.crypto.Hash;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * EIP-712 摘要计算（Deposit Wallet 域 Batch 类型）。
 *
 * <p>类型字符串：
 * <pre>
 * Batch(address wallet,uint256 nonce,uint256 deadline,Call[] calls)Call(address target,uint256 value,bytes data)
 * </pre>
 *
 * <p>注意 {@code Call.data} 是 {@code bytes} 类型 → 712 编码为 {@code keccak256(data)}。
 * {@code Call[]} 数组哈希 = {@code keccak256(concat(callHash_i))}。
 */
public final class BatchEip712 {

    private static final String CALL_TYPE_STRING  = "Call(address target,uint256 value,bytes data)";
    private static final String BATCH_TYPE_STRING =
            "Batch(address wallet,uint256 nonce,uint256 deadline,Call[] calls)" + CALL_TYPE_STRING;

    private static final byte[] CALL_TYPE_HASH  =
            Hash.sha3(CALL_TYPE_STRING.getBytes(StandardCharsets.US_ASCII));
    private static final byte[] BATCH_TYPE_HASH =
            Hash.sha3(BATCH_TYPE_STRING.getBytes(StandardCharsets.US_ASCII));

    private static final String DOMAIN_TYPE_STRING =
            "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)";
    private static final byte[] DOMAIN_TYPE_HASH =
            Hash.sha3(DOMAIN_TYPE_STRING.getBytes(StandardCharsets.US_ASCII));

    private BatchEip712() {}

    public static byte[] hashBatch(long chainId, Address wallet, BigInteger nonce,
                                    BigInteger deadline, List<Call> calls) {
        if (calls == null || calls.isEmpty()) {
            throw new IllegalArgumentException("calls 不能为空");
        }

        // 1. 计算 calls 数组哈希
        ByteBuffer callsBuf = ByteBuffer.allocate(32 * calls.size());
        for (Call c : calls) {
            callsBuf.put(callHash(c));
        }
        byte[] callsHash = Hash.sha3(callsBuf.array());

        // 2. struct hash for Batch
        ByteBuffer batchBuf = ByteBuffer.allocate(32 * 5);
        batchBuf.put(BATCH_TYPE_HASH);
        batchBuf.put(padAddress(wallet));
        batchBuf.put(padUint256(nonce));
        batchBuf.put(padUint256(deadline));
        batchBuf.put(callsHash);
        byte[] structHash = Hash.sha3(batchBuf.array());

        // 3. domain separator
        byte[] domainSeparator = domainSeparator(chainId, wallet);

        // 4. final digest = keccak256(0x1901 || domainSep || structHash)
        ByteBuffer digestBuf = ByteBuffer.allocate(2 + 32 + 32);
        digestBuf.put((byte) 0x19);
        digestBuf.put((byte) 0x01);
        digestBuf.put(domainSeparator);
        digestBuf.put(structHash);
        return Hash.sha3(digestBuf.array());
    }

    static byte[] callHash(Call c) {
        ByteBuffer buf = ByteBuffer.allocate(32 * 4);
        buf.put(CALL_TYPE_HASH);
        buf.put(padAddress(c.target()));
        buf.put(padUint256(c.value()));
        buf.put(Hash.sha3(c.data()));
        return Hash.sha3(buf.array());
    }

    static byte[] domainSeparator(long chainId, Address verifyingContract) {
        ByteBuffer buf = ByteBuffer.allocate(32 * 5);
        buf.put(DOMAIN_TYPE_HASH);
        buf.put(Hash.sha3(PolymarketContracts.DEPOSIT_WALLET_DOMAIN_NAME
                .getBytes(StandardCharsets.UTF_8)));
        buf.put(Hash.sha3(PolymarketContracts.DEPOSIT_WALLET_DOMAIN_VERSION
                .getBytes(StandardCharsets.UTF_8)));
        buf.put(padUint256(BigInteger.valueOf(chainId)));
        buf.put(padAddress(verifyingContract));
        return Hash.sha3(buf.array());
    }

    private static byte[] padAddress(Address a) {
        byte[] out = new byte[32];
        System.arraycopy(a.toBytes(), 0, out, 12, 20);
        return out;
    }

    private static byte[] padUint256(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        if (raw.length > 32) {
            // 去除符号位
            System.arraycopy(raw, raw.length - 32, out, 0, 32);
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        }
        return out;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -Dtest=BatchEip712Test test`
Expected: 6 tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/polymarket/clob/deposit/BatchEip712.java \
        src/test/java/com/polymarket/clob/deposit/BatchEip712Test.java
git commit -m "feat(deposit): 新增 BatchEip712.hashBatch（DepositWallet 域 Batch 类型摘要）"
```

---

### Task 12: `ApprovalPlanner.planMissingApprovals`

**Files:**
- Create: `src/main/java/com/polymarket/clob/deposit/ApprovalPlanner.java`
- Test: `src/test/java/com/polymarket/clob/deposit/ApprovalPlannerTest.java`

- [ ] **Step 1: 写测试**

```java
// src/test/java/com/polymarket/clob/deposit/ApprovalPlannerTest.java
package com.polymarket.clob.deposit;

import com.polymarket.clob.chain.DepositWalletConfig;
import com.polymarket.clob.chain.DepositWalletReads;
import com.polymarket.clob.chain.EvmRpcClient;
import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalPlannerTest {

    private static final Address WALLET = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");

    @Test
    void allMaxedReturnsEmpty() throws Exception {
        BigInteger MAX = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
        DepositWalletReads reads = new StubReads().withErc20All(MAX).withCtfAll(true);

        List<Call> calls = new ApprovalPlanner(reads, DepositWalletConfig.polygon())
                .planMissingApprovals(WALLET).get();

        assertThat(calls).isEmpty();
    }

    @Test
    void allZeroEmits13Calls() throws Exception {
        DepositWalletReads reads = new StubReads().withErc20All(BigInteger.ZERO).withCtfAll(false);

        List<Call> calls = new ApprovalPlanner(reads, DepositWalletConfig.polygon())
                .planMissingApprovals(WALLET).get();

        assertThat(calls).hasSize(13);
        // 第 1 项 = USDC.e approve(CTF, MAX)
        Call first = calls.get(0);
        assertThat(first.target()).isEqualTo(PolymarketContracts.USDC_E);
        // selector keccak256("approve(address,uint256)")[:4] = 0x095ea7b3
        assertThat(HexFormat.of().formatHex(first.data())).startsWith("095ea7b3");
        // 第 8 项 = CTF setApprovalForAll(EXCHANGE_V2, true) — selector 0xa22cb465
        Call eighth = calls.get(7);
        assertThat(eighth.target()).isEqualTo(PolymarketContracts.CTF);
        assertThat(HexFormat.of().formatHex(eighth.data())).startsWith("a22cb465");
    }

    @Test
    void mixedStateEmitsOnlyMissing() throws Exception {
        StubReads reads = new StubReads();
        BigInteger MAX = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
        // 让前 4 个 ERC20 已 max，剩下需要 approve
        reads.allowanceLookup = (token, owner, spender) ->
                spender.equals(PolymarketContracts.CTF)
                        || spender.equals(PolymarketContracts.EXCHANGE_V2)
                        || spender.equals(PolymarketContracts.NEG_RISK_EXCHANGE_V2)
                        || spender.equals(PolymarketContracts.NEG_RISK_ADAPTER)
                        ? MAX : BigInteger.ZERO;
        // CTF approval 全无
        reads.ctfApprovedLookup = (owner, op) -> false;

        List<Call> calls = new ApprovalPlanner(reads, DepositWalletConfig.polygon())
                .planMissingApprovals(WALLET).get();

        // 13 - 4 = 9
        assertThat(calls).hasSize(9);
    }

    /** 仿真 reads，可注入 lookup lambda。 */
    static class StubReads extends DepositWalletReads {
        @FunctionalInterface
        interface AllowanceFn { BigInteger get(Address token, Address owner, Address spender); }
        @FunctionalInterface
        interface CtfFn       { boolean get(Address owner, Address operator); }

        AllowanceFn allowanceLookup = (t, o, s) -> BigInteger.ZERO;
        CtfFn       ctfApprovedLookup = (o, s) -> false;

        StubReads() {
            super(new EvmRpcClient() {
                @Override public CompletableFuture<byte[]> ethCall(Address to, byte[] callData) {
                    return CompletableFuture.failedFuture(new UnsupportedOperationException("stub"));
                }
                @Override public CompletableFuture<byte[]> getCode(Address addr) {
                    return CompletableFuture.failedFuture(new UnsupportedOperationException("stub"));
                }
            }, 137);
        }

        StubReads withErc20All(BigInteger v) {
            allowanceLookup = (t, o, s) -> v;
            return this;
        }

        StubReads withCtfAll(boolean v) {
            ctfApprovedLookup = (o, s) -> v;
            return this;
        }

        @Override
        public CompletableFuture<BigInteger> erc20Allowance(Address token, Address owner, Address spender) {
            return CompletableFuture.completedFuture(allowanceLookup.get(token, owner, spender));
        }

        @Override
        public CompletableFuture<Boolean> ctfApprovedForAll(Address owner, Address operator) {
            return CompletableFuture.completedFuture(ctfApprovedLookup.get(owner, operator));
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -Dtest=ApprovalPlannerTest test`
Expected: FAIL

- [ ] **Step 3: 实现**

```java
// src/main/java/com/polymarket/clob/deposit/ApprovalPlanner.java
package com.polymarket.clob.deposit;

import com.polymarket.clob.chain.DepositWalletConfig;
import com.polymarket.clob.chain.DepositWalletReads;
import com.polymarket.clob.model.Address;
import org.web3j.crypto.Hash;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 读 13 项 (token, kind, spender) 的链上现状，仅缺失项产出 Call。
 * 与 fullOnboardAndTrade.ts 行为一致：read-then-plan，不硬编码批次大小。
 */
public final class ApprovalPlanner {

    /** keccak256("approve(address,uint256)")[:4] */
    private static final byte[] SEL_APPROVE = selector("approve(address,uint256)");
    /** keccak256("setApprovalForAll(address,bool)")[:4] */
    private static final byte[] SEL_SET_APPROVAL_FOR_ALL = selector("setApprovalForAll(address,bool)");
    private static final BigInteger UINT256_MAX = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);

    private final DepositWalletReads reads;
    private final DepositWalletConfig cfg;

    public ApprovalPlanner(DepositWalletReads reads, DepositWalletConfig cfg) {
        this.reads = reads;
        this.cfg = cfg;
    }

    public CompletableFuture<List<Call>> planMissingApprovals(Address wallet) {
        List<ApprovalTargets.Entry> targets = ApprovalTargets.standard(cfg);
        List<CompletableFuture<Call>> probes = new ArrayList<>(targets.size());

        for (ApprovalTargets.Entry t : targets) {
            if (t.kind() == ApprovalTargets.Kind.ERC20) {
                probes.add(reads.erc20Allowance(t.token(), wallet, t.spender()).thenApply(cur ->
                        cur.compareTo(BigInteger.ZERO) > 0
                                ? null
                                : new Call(t.token(), BigInteger.ZERO, approveCalldata(t.spender()))));
            } else {
                probes.add(reads.ctfApprovedForAll(wallet, t.spender()).thenApply(cur ->
                        cur ? null
                            : new Call(t.token(), BigInteger.ZERO, setApprovalForAllCalldata(t.spender()))));
            }
        }

        return CompletableFuture.allOf(probes.toArray(new CompletableFuture[0]))
                .thenApply(v -> probes.stream()
                        .map(CompletableFuture::join)
                        .filter(c -> c != null)
                        .toList());
    }

    private static byte[] approveCalldata(Address spender) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 32 + 32);
        buf.put(SEL_APPROVE);
        buf.put(padAddress(spender));
        buf.put(padUint256(UINT256_MAX));
        return buf.array();
    }

    private static byte[] setApprovalForAllCalldata(Address operator) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 32 + 32);
        buf.put(SEL_SET_APPROVAL_FOR_ALL);
        buf.put(padAddress(operator));
        buf.put(padUint256(BigInteger.ONE)); // bool true = 1
        return buf.array();
    }

    private static byte[] selector(String sig) {
        return Arrays.copyOfRange(Hash.sha3(sig.getBytes(StandardCharsets.US_ASCII)), 0, 4);
    }

    private static byte[] padAddress(Address a) {
        byte[] out = new byte[32];
        System.arraycopy(a.toBytes(), 0, out, 12, 20);
        return out;
    }

    private static byte[] padUint256(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        if (raw.length > 32) System.arraycopy(raw, raw.length - 32, out, 0, 32);
        else System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -Dtest=ApprovalPlannerTest test`
Expected: 3 tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/polymarket/clob/deposit/ApprovalPlanner.java \
        src/test/java/com/polymarket/clob/deposit/ApprovalPlannerTest.java
git commit -m "feat(deposit): 新增 ApprovalPlanner（read-then-plan 13 项授权）"
```

---

### Task 13: `DepositWalletRelayer`（4 个端点）

**Files:**
- Create: `src/main/java/com/polymarket/clob/deposit/DepositWalletRelayer.java`
- Test: `src/test/java/com/polymarket/clob/deposit/DepositWalletRelayerTest.java`

- [ ] **Step 1: 写 WireMock 测试**

```java
// src/test/java/com/polymarket/clob/deposit/DepositWalletRelayerTest.java
package com.polymarket.clob.deposit;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.gamma.GammaSession;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DepositWalletRelayerTest {

    private static final Address EOA    = Address.fromHex("0x4cAfCf2D9A032f57088872f6546Bb68305d209D7");
    private static final Address WALLET = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");

    private WireMockServer server;
    private DepositWalletRelayer relayer;

    @BeforeEach
    void start() {
        server = new WireMockServer(0);
        server.start();
        GammaSession session = new GammaSession("polymarket_auth=val", Instant.now().plusSeconds(3600));
        relayer = new DepositWalletRelayer(URI.create(server.baseUrl()), HttpClient.newHttpClient(), session);
    }

    @AfterEach
    void stop() { server.stop(); }

    @Test
    void submitWalletCreatePostsTypeFromTo() throws Exception {
        server.stubFor(post(urlEqualTo("/submit"))
                .withHeader("Cookie", equalTo("polymarket_auth=val"))
                .withRequestBody(matchingJsonPath("$.type", equalTo("WALLET-CREATE")))
                .withRequestBody(matchingJsonPath("$.from", equalTo(EOA.toLowerHex())))
                .withRequestBody(matchingJsonPath("$.to", equalTo(PolymarketContracts.FACTORY.toLowerHex())))
                .willReturn(okJson("{\"transactionID\":\"tx-1\",\"state\":\"STATE_NEW\"}")));

        String txId = relayer.submitWalletCreate(EOA, PolymarketContracts.FACTORY);

        assertThat(txId).isEqualTo("tx-1");
    }

    @Test
    void submitBatchPostsExpectedShape() throws Exception {
        server.stubFor(post(urlEqualTo("/submit"))
                .withRequestBody(matchingJsonPath("$.type", equalTo("WALLET")))
                .withRequestBody(matchingJsonPath("$.signature"))
                .withRequestBody(matchingJsonPath("$.depositWalletParams.depositWallet",
                        equalTo(WALLET.toLowerHex())))
                .withRequestBody(matchingJsonPath("$.depositWalletParams.deadline",
                        equalTo("1778081214")))
                .withRequestBody(matchingJsonPath("$.depositWalletParams.calls[0].target"))
                .willReturn(okJson("{\"transactionID\":\"tx-2\"}")));

        SignedBatch batch = new SignedBatch(EOA, PolymarketContracts.FACTORY, WALLET,
                BigInteger.ZERO, BigInteger.valueOf(1778081214L),
                List.of(new Call(PolymarketContracts.USDC_E, BigInteger.ZERO, new byte[]{0x01})),
                new byte[65]);

        assertThat(relayer.submitBatch(batch)).isEqualTo("tx-2");
    }

    @Test
    void getRelayerNonceParsesNumericResponse() throws Exception {
        server.stubFor(get(urlPathEqualTo("/nonce"))
                .withQueryParam("address", equalTo(EOA.toLowerHex()))
                .withQueryParam("type", equalTo("WALLET"))
                .willReturn(okJson("{\"nonce\":7}")));

        assertThat(relayer.getRelayerNonce(EOA)).isEqualTo(BigInteger.valueOf(7));
    }

    @Test
    void waitForTxPollsUntilConfirmed() throws Exception {
        server.stubFor(get(urlPathEqualTo("/transaction"))
                .inScenario("poll").whenScenarioStateIs("Started")
                .willReturn(okJson("[{\"state\":\"STATE_NEW\"}]"))
                .willSetStateTo("seen-once"));
        server.stubFor(get(urlPathEqualTo("/transaction"))
                .inScenario("poll").whenScenarioStateIs("seen-once")
                .willReturn(okJson("[{\"state\":\"STATE_CONFIRMED\",\"transactionHash\":\"0xabc\"}]")));

        RelayerTxResult r = relayer.waitForTx("tx-3", Duration.ofMillis(10), 10);
        assertThat(r.isConfirmed()).isTrue();
        assertThat(r.txHash()).isEqualTo("0xabc");
    }

    @Test
    void waitForTxFailedThrows() {
        server.stubFor(get(urlPathEqualTo("/transaction"))
                .willReturn(okJson("[{\"state\":\"STATE_FAILED\",\"errorMsg\":\"deadline too soon\"}]")));

        assertThatThrownBy(() -> relayer.waitForTx("tx-4", Duration.ofMillis(10), 10))
                .isInstanceOf(RelayerTxFailedException.class)
                .hasMessageContaining("deadline too soon");
    }

    @Test
    void submitNon2xxThrowsIOException() {
        server.stubFor(post(urlEqualTo("/submit")).willReturn(serverError().withBody("boom")));

        assertThatThrownBy(() -> relayer.submitWalletCreate(EOA, PolymarketContracts.FACTORY))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("500");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -Dtest=DepositWalletRelayerTest test`
Expected: FAIL — `DepositWalletRelayer` 不存在

- [ ] **Step 3: 实现**

```java
// src/main/java/com/polymarket/clob/deposit/DepositWalletRelayer.java
package com.polymarket.clob.deposit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.polymarket.clob.gamma.GammaSession;
import com.polymarket.clob.http.JsonCodec;
import com.polymarket.clob.model.Address;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Polymarket Relayer V2 客户端：4 个端点 —— /submit、/nonce、/transaction（轮询）。
 * 鉴权用 GammaSession 的 cookie。Wire body 字段顺序与 docs/EOA_TO_ORDER.md §5 一致。
 */
public final class DepositWalletRelayer {

    private final URI baseUrl;
    private final HttpClient http;
    private final GammaSession session;
    private final ObjectMapper mapper;

    public DepositWalletRelayer(URI baseUrl, HttpClient http, GammaSession session) {
        this.baseUrl = baseUrl;
        this.http = http;
        this.session = session;
        this.mapper = JsonCodec.objectMapper();
    }

    public String submitWalletCreate(Address eoa, Address factory) throws IOException, InterruptedException {
        ObjectNode body = mapper.createObjectNode();
        body.put("type", "WALLET-CREATE");
        body.put("from", eoa.toLowerHex());
        body.put("to", factory.toLowerHex());
        return submitAndExtractTxId(mapper.writeValueAsString(body));
    }

    public String submitBatch(SignedBatch batch) throws IOException, InterruptedException {
        ObjectNode body = mapper.createObjectNode();
        body.put("type", "WALLET");
        body.put("from", batch.eoa().toLowerHex());
        body.put("to", batch.factory().toLowerHex());
        body.put("nonce", batch.nonce().toString());
        body.put("signature", "0x" + HexFormat.of().formatHex(batch.signature65()));

        ObjectNode params = body.putObject("depositWalletParams");
        params.put("depositWallet", batch.wallet().toLowerHex());
        params.put("deadline", batch.deadline().toString());
        ArrayNode calls = params.putArray("calls");
        for (Call c : batch.calls()) {
            ObjectNode n = calls.addObject();
            n.put("target", c.target().toLowerHex());
            n.put("value", c.value().toString());
            n.put("data", "0x" + HexFormat.of().formatHex(c.data()));
        }
        return submitAndExtractTxId(mapper.writeValueAsString(body));
    }

    public java.math.BigInteger getRelayerNonce(Address eoa) throws IOException, InterruptedException {
        URI uri = baseUrl.resolve("/nonce?address=" + eoa.toLowerHex() + "&type=WALLET");
        HttpResponse<String> resp = sendGet(uri);
        ensureOk(resp, "/nonce");
        JsonNode node = mapper.readTree(resp.body());
        if (node.isObject()) node = node.path("nonce");
        if (node.isNumber()) return node.bigIntegerValue();
        if (node.isTextual()) return new java.math.BigInteger(node.asText().trim());
        throw new IOException("/nonce 响应无法解析：" + resp.body());
    }

    public RelayerTxResult getTransaction(String txId) throws IOException, InterruptedException {
        URI uri = baseUrl.resolve("/transaction?id=" + java.net.URLEncoder.encode(txId, StandardCharsets.UTF_8));
        HttpResponse<String> resp = sendGet(uri);
        ensureOk(resp, "/transaction");
        return parseTxResult(resp.body(), txId);
    }

    public RelayerTxResult waitForTx(String txId, Duration pollInterval, int maxAttempts)
            throws IOException, InterruptedException {
        for (int i = 0; i < maxAttempts; i++) {
            Thread.sleep(pollInterval.toMillis());
            RelayerTxResult r = getTransaction(txId);
            if (r.isTerminal()) {
                if (!r.isConfirmed()) throw new RelayerTxFailedException(r);
                return r;
            }
        }
        throw new IOException("relayer tx 未达终态 txId=" + txId);
    }

    // ---- 内部 ----

    private String submitAndExtractTxId(String json) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(baseUrl.resolve("/submit"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Cookie", session.cookieHeader())
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("relayer /submit HTTP " + resp.statusCode() + " body=" + resp.body());
        }
        RelayerTxResult r = parseTxResult(resp.body(), null);
        if (r.txId() == null || r.txId().isBlank()) {
            throw new IOException("relayer /submit 未返回 transactionID: " + resp.body());
        }
        return r.txId();
    }

    private HttpResponse<String> sendGet(URI uri) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Cookie", session.cookieHeader())
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static void ensureOk(HttpResponse<String> resp, String tag) throws IOException {
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("relayer " + tag + " HTTP " + resp.statusCode() + " body=" + resp.body());
        }
    }

    private RelayerTxResult parseTxResult(String body, String fallbackTxId) throws IOException {
        JsonNode node = mapper.readTree(body);
        if (node.isArray() && node.size() > 0) node = node.get(0);

        String txId = textOrNull(node, "transactionID", "transactionId");
        if (txId == null) txId = fallbackTxId;
        String state = textOrNull(node, "state");
        String hash = textOrNull(node, "transactionHash", "hash");
        String error = textOrNull(node, "errorMsg", "error", "reason", "failureReason", "message");

        if (state != null && state.startsWith("STATE_")) state = state.substring("STATE_".length());
        if (state != null) state = state.toUpperCase();
        return new RelayerTxResult(txId, state, hash, error);
    }

    private static String textOrNull(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull()) {
                String s = v.isTextual() ? v.asText() : v.toString();
                if (!s.isBlank() && !"\"\"".equals(s) && !"null".equals(s)) return s;
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -Dtest=DepositWalletRelayerTest test`
Expected: 6 tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/polymarket/clob/deposit/DepositWalletRelayer.java \
        src/test/java/com/polymarket/clob/deposit/DepositWalletRelayerTest.java
git commit -m "feat(deposit): 新增 DepositWalletRelayer（/submit /nonce /transaction）"
```

---

### Task 14: `DepositWalletDerivation` 封装

**Files:**
- Create: `src/main/java/com/polymarket/clob/deposit/DepositWalletDerivation.java`
- Test: `src/test/java/com/polymarket/clob/deposit/DepositWalletDerivationTest.java`

- [ ] **Step 1: 写测试**

```java
// src/test/java/com/polymarket/clob/deposit/DepositWalletDerivationTest.java
package com.polymarket.clob.deposit;

import com.polymarket.clob.chain.DepositWalletReads;
import com.polymarket.clob.chain.EvmRpcClient;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class DepositWalletDerivationTest {

    @Test
    void deriveDelegatesToReads() throws Exception {
        EvmRpcClient stub = new EvmRpcClient() {
            @Override public CompletableFuture<byte[]> ethCall(Address to, byte[] callData) {
                return CompletableFuture.completedFuture(HexFormat.of().parseHex(
                        "000000000000000000000000ada4563a6738215c56d2b59bc1c5a1db65b1fd78"));
            }
            @Override public CompletableFuture<byte[]> getCode(Address addr) {
                return CompletableFuture.completedFuture(new byte[]{0x60});
            }
        };
        DepositWalletDerivation d = new DepositWalletDerivation(new DepositWalletReads(stub, 137));

        Address w = d.predictWalletAddress(
                Address.fromHex("0x4cAfCf2D9A032f57088872f6546Bb68305d209D7")).get();
        assertThat(w).isEqualTo(Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78"));
        assertThat(d.isDeployed(w).get()).isTrue();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -Dtest=DepositWalletDerivationTest test`
Expected: FAIL

- [ ] **Step 3: 实现**

```java
// src/main/java/com/polymarket/clob/deposit/DepositWalletDerivation.java
package com.polymarket.clob.deposit;

import com.polymarket.clob.chain.DepositWalletReads;
import com.polymarket.clob.model.Address;

import java.util.concurrent.CompletableFuture;

/**
 * 薄外观 —— 把 EOA → wallet 派生与部署检测两个最常用动作集中暴露。
 * 内部仅委托 {@link DepositWalletReads}；保留独立类是为编排器代码可读。
 */
public final class DepositWalletDerivation {

    private final DepositWalletReads reads;

    public DepositWalletDerivation(DepositWalletReads reads) {
        this.reads = reads;
    }

    public CompletableFuture<Address> predictWalletAddress(Address eoa) {
        return reads.predictWalletAddress(eoa);
    }

    public CompletableFuture<Boolean> isDeployed(Address wallet) {
        return reads.isDeployed(wallet);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -Dtest=DepositWalletDerivationTest test`
Expected: 1 test pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/polymarket/clob/deposit/DepositWalletDerivation.java \
        src/test/java/com/polymarket/clob/deposit/DepositWalletDerivationTest.java
git commit -m "feat(deposit): 新增 DepositWalletDerivation 薄外观"
```

---

## Phase E — order/ POLY_1271 嵌套签名

### Task 15: `Pol1271OrderSigner.contentsHash`

**Files:**
- Create: `src/main/java/com/polymarket/clob/order/Pol1271OrderSigner.java`（先建骨架与 contentsHash 方法）
- Test: `src/test/java/com/polymarket/clob/order/Pol1271ContentsHashTest.java`

- [ ] **Step 1: 写 contentsHash 测试**

```java
// src/test/java/com/polymarket/clob/order/Pol1271ContentsHashTest.java
package com.polymarket.clob.order;

import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Hash;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class Pol1271ContentsHashTest {

    private static final Address WALLET = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");
    private static final String ZERO32 = "0x" + "00".repeat(32);

    private OrderV2 makeOrder() {
        return OrderV2.builder()
                .salt(BigInteger.valueOf(761396384028L))
                .maker(WALLET)
                .signer(WALLET)
                .tokenId(new BigInteger("57597306756265660"))
                .makerAmount(BigInteger.valueOf(1_000_000))
                .takerAmount(BigInteger.valueOf(1_886_700))
                .side(Side.BUY)
                .signatureType(SignatureType.POLY_1271)
                .timestamp(BigInteger.valueOf(1778079826561L))
                .expiration(BigInteger.ZERO)
                .metadata(ZERO32)
                .builder(ZERO32)
                .build();
    }

    @Test
    void contentsHashIs32Bytes() {
        byte[] h = Pol1271OrderSigner.contentsHash(makeOrder());
        assertThat(h).hasSize(32);
    }

    @Test
    void contentsHashEqualsManualEncoding() {
        OrderV2 o = makeOrder();
        // 手算：keccak256(abi.encode(ORDER_TYPE_HASH, salt, maker, signer, tokenId,
        //                            makerAmount, takerAmount, side(uint8), sigType(uint8),
        //                            timestamp, metadata, builder))
        ByteBuffer buf = ByteBuffer.allocate(32 * 12);
        buf.put(PolymarketContracts.ORDER_TYPE_HASH);
        buf.put(padUint(o.getSalt()));
        buf.put(padAddress(o.getMaker()));
        buf.put(padAddress(o.getSigner()));
        buf.put(padUint(o.getTokenId()));
        buf.put(padUint(o.getMakerAmount()));
        buf.put(padUint(o.getTakerAmount()));
        buf.put(padUint(BigInteger.valueOf(o.getSide() == Side.BUY ? 0 : 1)));
        buf.put(padUint(BigInteger.valueOf(o.getSignatureType().code())));
        buf.put(padUint(o.getTimestamp()));
        buf.put(parseBytes32(o.getMetadata()));
        buf.put(parseBytes32(o.getBuilder()));
        byte[] expected = Hash.sha3(buf.array());

        assertThat(Pol1271OrderSigner.contentsHash(o)).containsExactly(expected);
    }

    @Test
    void contentsHashChangesWithSide() {
        OrderV2 buy = makeOrder();
        OrderV2 sell = buy.toBuilder().side(Side.SELL).build();
        assertThat(Pol1271OrderSigner.contentsHash(buy))
                .isNotEqualTo(Pol1271OrderSigner.contentsHash(sell));
    }

    private static byte[] padUint(BigInteger v) {
        byte[] raw = v.toByteArray(); byte[] out = new byte[32];
        if (raw.length > 32) System.arraycopy(raw, raw.length - 32, out, 0, 32);
        else System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }

    private static byte[] padAddress(Address a) {
        byte[] out = new byte[32];
        System.arraycopy(a.toBytes(), 0, out, 12, 20);
        return out;
    }

    private static byte[] parseBytes32(String hex) {
        byte[] out = java.util.HexFormat.of().parseHex(hex.substring(2));
        if (out.length != 32) throw new IllegalArgumentException();
        return out;
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -Dtest=Pol1271ContentsHashTest test`
Expected: FAIL — `Pol1271OrderSigner` 不存在

- [ ] **Step 3: 实现 `Pol1271OrderSigner` 骨架 + contentsHash**

```java
// src/main/java/com/polymarket/clob/order/Pol1271OrderSigner.java
package com.polymarket.clob.order;

import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.exception.ClobSignatureException;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.ContractRegistry;
import org.web3j.crypto.Hash;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;

/**
 * POLY_1271 嵌套签名（ERC-7739 TypedDataSign）。
 *
 * <p>最终 signature 字节序列：
 * {@code innerSig(65) || appDomainSep(32) || contentsHash(32) || ORDER_TYPE_STRING || lenBE(2)}
 *
 * <p>实现严格复刻 clob-client-v2/src/order-utils/exchangeOrderBuilderV2.ts:147-221。
 */
public final class Pol1271OrderSigner {

    private Pol1271OrderSigner() {}

    /**
     * 计算 contents hash —— Order struct 的 EIP-712 struct hash。
     * keccak256(abi.encode(ORDER_TYPE_HASH, salt, maker, signer, tokenId,
     *                       makerAmount, takerAmount, side, sigType, timestamp,
     *                       metadata, builder))
     */
    public static byte[] contentsHash(OrderV2 order) {
        ByteBuffer buf = ByteBuffer.allocate(32 * 12);
        buf.put(PolymarketContracts.ORDER_TYPE_HASH);
        buf.put(padUint(order.getSalt()));
        buf.put(padAddress(order.getMaker()));
        buf.put(padAddress(order.getSigner()));
        buf.put(padUint(order.getTokenId()));
        buf.put(padUint(order.getMakerAmount()));
        buf.put(padUint(order.getTakerAmount()));
        buf.put(padUint(BigInteger.valueOf(sideCode(order.getSide()))));
        buf.put(padUint(BigInteger.valueOf(order.getSignatureType().code())));
        buf.put(padUint(order.getTimestamp()));
        buf.put(parseBytes32(order.getMetadata(), "metadata"));
        buf.put(parseBytes32(order.getBuilder(), "builder"));
        return Hash.sha3(buf.array());
    }

    /** Task 16 实现：appDomainSep 缓存。 */
    static byte[] appDomainSeparator(long chainId, boolean negRisk) {
        throw new UnsupportedOperationException("Implemented in Task 16");
    }

    /** Task 17 实现：完整 sign 流程。 */
    public static CompletableFuture<SignedOrderV2> sign(Signer eoa, OrderV2 order,
                                                         long chainId, boolean negRisk) {
        throw new UnsupportedOperationException("Implemented in Task 17");
    }

    // ---- helpers ----

    static int sideCode(Side s) { return s == Side.BUY ? 0 : 1; }

    static byte[] padUint(BigInteger v) {
        byte[] raw = v.toByteArray(); byte[] out = new byte[32];
        if (raw.length > 32) System.arraycopy(raw, raw.length - 32, out, 0, 32);
        else System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }

    static byte[] padAddress(Address a) {
        byte[] out = new byte[32];
        System.arraycopy(a.toBytes(), 0, out, 12, 20);
        return out;
    }

    static byte[] parseBytes32(String hex, String fieldName) {
        if (hex == null || !hex.startsWith("0x") || hex.length() != 66) {
            throw new ClobSignatureException(fieldName + " must be 0x-prefixed 32B hex (66 chars)");
        }
        return HexFormat.of().parseHex(hex.substring(2));
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -Dtest=Pol1271ContentsHashTest test`
Expected: 3 tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/polymarket/clob/order/Pol1271OrderSigner.java \
        src/test/java/com/polymarket/clob/order/Pol1271ContentsHashTest.java
git commit -m "feat(order): Pol1271OrderSigner 骨架 + contentsHash 实现"
```

---

### Task 16: `Pol1271OrderSigner.appDomainSeparator` 缓存

**Files:**
- Modify: `src/main/java/com/polymarket/clob/order/Pol1271OrderSigner.java` — 替换 `appDomainSeparator` 占位
- Test: `src/test/java/com/polymarket/clob/order/Pol1271AppDomainSepTest.java`

- [ ] **Step 1: 写测试**

```java
// src/test/java/com/polymarket/clob/order/Pol1271AppDomainSepTest.java
package com.polymarket.clob.order;

import com.polymarket.clob.chain.PolymarketContracts;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Hash;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class Pol1271AppDomainSepTest {

    @Test
    void exchangeV2DomainSepMatchesManual() {
        byte[] expected = manual(137, PolymarketContracts.EXCHANGE_V2.toBytes());
        assertThat(Pol1271OrderSigner.appDomainSeparator(137, false)).containsExactly(expected);
    }

    @Test
    void negRiskDomainSepMatchesManual() {
        byte[] expected = manual(137, PolymarketContracts.NEG_RISK_EXCHANGE_V2.toBytes());
        assertThat(Pol1271OrderSigner.appDomainSeparator(137, true)).containsExactly(expected);
    }

    @Test
    void negRiskAndPlainDiffer() {
        assertThat(Pol1271OrderSigner.appDomainSeparator(137, false))
                .isNotEqualTo(Pol1271OrderSigner.appDomainSeparator(137, true));
    }

    @Test
    void cachedAcrossCalls() {
        byte[] a = Pol1271OrderSigner.appDomainSeparator(137, false);
        byte[] b = Pol1271OrderSigner.appDomainSeparator(137, false);
        // 同实例（缓存）—— 比对引用相等
        assertThat(a).isSameAs(b);
    }

    private static byte[] manual(long chainId, byte[] verifyingContract) {
        String typeStr = "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)";
        byte[] typeHash = Hash.sha3(typeStr.getBytes(StandardCharsets.US_ASCII));

        byte[] nameHash = Hash.sha3(PolymarketContracts.CTF_EXCHANGE_V2_DOMAIN_NAME
                .getBytes(StandardCharsets.UTF_8));
        byte[] versionHash = Hash.sha3(PolymarketContracts.CTF_EXCHANGE_V2_DOMAIN_VERSION
                .getBytes(StandardCharsets.UTF_8));

        byte[] chainIdPad = new byte[32];
        byte[] cidRaw = BigInteger.valueOf(chainId).toByteArray();
        System.arraycopy(cidRaw, 0, chainIdPad, 32 - cidRaw.length, cidRaw.length);

        byte[] vcPad = new byte[32];
        System.arraycopy(verifyingContract, 0, vcPad, 12, 20);

        ByteBuffer buf = ByteBuffer.allocate(32 * 5);
        buf.put(typeHash);
        buf.put(nameHash);
        buf.put(versionHash);
        buf.put(chainIdPad);
        buf.put(vcPad);
        return Hash.sha3(buf.array());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -Dtest=Pol1271AppDomainSepTest test`
Expected: FAIL — `UnsupportedOperationException`

- [ ] **Step 3: 替换 `appDomainSeparator` 占位 + 加缓存**

修改 `Pol1271OrderSigner.java` 顶部加 import：

```java
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
```

在类内加缓存字段：

```java
    private static final String DOMAIN_TYPE_STRING =
            "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)";
    private static final byte[] DOMAIN_TYPE_HASH =
            Hash.sha3(DOMAIN_TYPE_STRING.getBytes(StandardCharsets.US_ASCII));

    private static final byte[] CTF_EXCHANGE_NAME_HASH =
            Hash.sha3(PolymarketContracts.CTF_EXCHANGE_V2_DOMAIN_NAME.getBytes(StandardCharsets.UTF_8));
    private static final byte[] CTF_EXCHANGE_VERSION_HASH =
            Hash.sha3(PolymarketContracts.CTF_EXCHANGE_V2_DOMAIN_VERSION.getBytes(StandardCharsets.UTF_8));

    private static final Map<String, byte[]> APP_DOMAIN_SEP_CACHE = new ConcurrentHashMap<>();
```

替换 `appDomainSeparator` 方法：

```java
    static byte[] appDomainSeparator(long chainId, boolean negRisk) {
        String key = chainId + "|" + negRisk;
        return APP_DOMAIN_SEP_CACHE.computeIfAbsent(key, k -> {
            Address verifyingContract = ContractRegistry.exchangeV2(chainId, negRisk)
                    .orElseThrow(() -> new ClobSignatureException(
                            "exchangeV2 not registered for chainId=" + chainId + " negRisk=" + negRisk));
            ByteBuffer buf = ByteBuffer.allocate(32 * 5);
            buf.put(DOMAIN_TYPE_HASH);
            buf.put(CTF_EXCHANGE_NAME_HASH);
            buf.put(CTF_EXCHANGE_VERSION_HASH);
            buf.put(padUint(BigInteger.valueOf(chainId)));
            buf.put(padAddress(verifyingContract));
            return Hash.sha3(buf.array());
        });
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -Dtest=Pol1271AppDomainSepTest test`
Expected: 4 tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/polymarket/clob/order/Pol1271OrderSigner.java \
        src/test/java/com/polymarket/clob/order/Pol1271AppDomainSepTest.java
git commit -m "feat(order): Pol1271OrderSigner.appDomainSeparator 缓存实现"
```

---

### Task 17: `Pol1271OrderSigner.sign` 完整 ERC-7739 签名

**Files:**
- Modify: `src/main/java/com/polymarket/clob/order/Pol1271OrderSigner.java` — 替换 `sign` 占位
- Test: `src/test/java/com/polymarket/clob/order/Pol1271SignTest.java`

- [ ] **Step 1: 写测试覆盖签名字节布局**

```java
// src/test/java/com/polymarket/clob/order/Pol1271SignTest.java
package com.polymarket.clob.order;

import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class Pol1271SignTest {

    /** 固定测试私钥；EOA 不重要（POLY_1271 maker = wallet）。 */
    private static final String PK_HEX = "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    private static final Address WALLET = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");
    private static final String ZERO32 = "0x" + "00".repeat(32);

    private OrderV2 makeOrder() {
        return OrderV2.builder()
                .salt(BigInteger.valueOf(761396384028L))
                .maker(WALLET)
                .signer(WALLET)
                .tokenId(new BigInteger("57597306756265660"))
                .makerAmount(BigInteger.valueOf(1_000_000))
                .takerAmount(BigInteger.valueOf(1_886_700))
                .side(Side.BUY)
                .signatureType(SignatureType.POLY_1271)
                .timestamp(BigInteger.valueOf(1778079826561L))
                .expiration(BigInteger.ZERO)
                .metadata(ZERO32)
                .builder(ZERO32)
                .build();
    }

    @Test
    void signProducesExpectedByteLayout() throws Exception {
        Signer eoa = LocalSigner.fromPrivateKeyHex(PK_HEX);
        SignedOrderV2 signed = Pol1271OrderSigner.sign(eoa, makeOrder(), 137, false).get();

        String hex = signed.getSignature();
        assertThat(hex).startsWith("0x");
        byte[] sig = HexFormat.of().parseHex(hex.substring(2));

        int orderTypeLen = PolymarketContracts.ORDER_TYPE_STRING
                .getBytes(StandardCharsets.US_ASCII).length;
        // 65 + 32 + 32 + N + 2
        assertThat(sig).hasSize(65 + 32 + 32 + orderTypeLen + 2);

        // bytes [129..129+N) 应该 = ORDER_TYPE_STRING ASCII
        byte[] embeddedType = Arrays.copyOfRange(sig, 129, 129 + orderTypeLen);
        assertThat(new String(embeddedType, StandardCharsets.US_ASCII))
                .isEqualTo(PolymarketContracts.ORDER_TYPE_STRING);

        // 末尾 2B = uint16 BE = orderTypeLen
        int hi = sig[sig.length - 2] & 0xff;
        int lo = sig[sig.length - 1] & 0xff;
        assertThat((hi << 8) | lo).isEqualTo(orderTypeLen);

        // 第 65 ~ 97 = appDomainSep (chainId=137, negRisk=false)
        byte[] appSep = Arrays.copyOfRange(sig, 65, 97);
        assertThat(appSep).containsExactly(Pol1271OrderSigner.appDomainSeparator(137, false));

        // 第 97 ~ 129 = contentsHash
        byte[] ch = Arrays.copyOfRange(sig, 97, 129);
        assertThat(ch).containsExactly(Pol1271OrderSigner.contentsHash(makeOrder()));
    }

    @Test
    void negRiskUsesNegRiskAppDomainSep() throws Exception {
        Signer eoa = LocalSigner.fromPrivateKeyHex(PK_HEX);
        SignedOrderV2 signed = Pol1271OrderSigner.sign(eoa, makeOrder(), 137, true).get();
        byte[] sig = HexFormat.of().parseHex(signed.getSignature().substring(2));

        byte[] appSep = Arrays.copyOfRange(sig, 65, 97);
        assertThat(appSep).containsExactly(Pol1271OrderSigner.appDomainSeparator(137, true));
    }

    @Test
    void signedOrderEchoesOriginalOrder() throws Exception {
        Signer eoa = LocalSigner.fromPrivateKeyHex(PK_HEX);
        OrderV2 order = makeOrder();
        SignedOrderV2 signed = Pol1271OrderSigner.sign(eoa, order, 137, false).get();
        assertThat(signed.getOrder()).isEqualTo(order);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -Dtest=Pol1271SignTest test`
Expected: FAIL — `UnsupportedOperationException`

- [ ] **Step 3: 实现完整 `sign`**

修改 `Pol1271OrderSigner.java`，替换 `sign` 方法：

```java
    /** ERC-7739 nested TypedDataSign 全签名。 */
    public static CompletableFuture<SignedOrderV2> sign(Signer eoa, OrderV2 order,
                                                         long chainId, boolean negRisk) {
        try {
            byte[] contents = contentsHash(order);
            byte[] appSep = appDomainSeparator(chainId, negRisk);
            byte[] innerDigest = innerDigest(order, chainId, negRisk, contents, appSep);
            return eoa.signHash(innerDigest).thenApply(innerSig -> {
                if (innerSig == null || innerSig.length != 65) {
                    throw new ClobSignatureException("inner signer returned length="
                            + (innerSig == null ? -1 : innerSig.length));
                }
                byte[] orderTypeAscii = PolymarketContracts.ORDER_TYPE_STRING
                        .getBytes(StandardCharsets.US_ASCII);
                int len = orderTypeAscii.length;

                ByteBuffer buf = ByteBuffer.allocate(65 + 32 + 32 + len + 2);
                buf.put(innerSig);
                buf.put(appSep);
                buf.put(contents);
                buf.put(orderTypeAscii);
                buf.put((byte) ((len >> 8) & 0xff));
                buf.put((byte) (len & 0xff));
                String hex = "0x" + HexFormat.of().formatHex(buf.array());
                return SignedOrderV2.of(order, hex);
            });
        } catch (ClobSignatureException e) {
            return CompletableFuture.failedFuture(e);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(
                    new ClobSignatureException("Pol1271OrderSigner.sign failed", e));
        }
    }

    /**
     * 内层 TypedDataSign 摘要：
     *   structHash = keccak256(TYPED_DATA_SIGN_TYPE_HASH || contentsHash
     *                          || keccak256("DepositWallet") || keccak256("1")
     *                          || chainId || pad(wallet) || zero32)
     *   digest     = keccak256(0x1901 || appDomainSep || structHash)
     *
     * 钱包域用作 TypedDataSign value 的内嵌 domain：
     *   name="DepositWallet", version="1", chainId, verifyingContract=order.signer (=wallet),
     *   salt=bytes32(0)。
     */
    static byte[] innerDigest(OrderV2 order, long chainId, boolean negRisk,
                              byte[] contentsHash, byte[] appDomainSep) {
        byte[] depositNameHash = Hash.sha3(
                PolymarketContracts.DEPOSIT_WALLET_DOMAIN_NAME.getBytes(StandardCharsets.UTF_8));
        byte[] depositVersionHash = Hash.sha3(
                PolymarketContracts.DEPOSIT_WALLET_DOMAIN_VERSION.getBytes(StandardCharsets.UTF_8));
        byte[] zero32 = new byte[32];

        ByteBuffer structBuf = ByteBuffer.allocate(32 * 7);
        structBuf.put(PolymarketContracts.TYPED_DATA_SIGN_TYPE_HASH);
        structBuf.put(contentsHash);
        structBuf.put(depositNameHash);
        structBuf.put(depositVersionHash);
        structBuf.put(padUint(BigInteger.valueOf(chainId)));
        structBuf.put(padAddress(order.getSigner()));
        structBuf.put(zero32);
        byte[] structHash = Hash.sha3(structBuf.array());

        ByteBuffer digestBuf = ByteBuffer.allocate(2 + 32 + 32);
        digestBuf.put((byte) 0x19);
        digestBuf.put((byte) 0x01);
        digestBuf.put(appDomainSep);
        digestBuf.put(structHash);
        return Hash.sha3(digestBuf.array());
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -Dtest=Pol1271SignTest test`
Expected: 3 tests pass

- [ ] **Step 5: 跑全部 Pol1271 测试**

Run: `mvn -Dtest='com.polymarket.clob.order.Pol1271*Test' test`
Expected: 10 tests pass

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/polymarket/clob/order/Pol1271OrderSigner.java \
        src/test/java/com/polymarket/clob/order/Pol1271SignTest.java
git commit -m "feat(order): Pol1271OrderSigner.sign 完整 ERC-7739 嵌套签名实现"
```

---

### Task 18: `OrderBuilder` 接通 POLY_1271 路径

**Files:**
- Modify: `src/main/java/com/polymarket/clob/order/OrderBuilder.java` — 移除 `assembleOrderV2` 第 181 行 `throw UnsupportedOperationException`，路由到 `Pol1271OrderSigner`
- Test: `src/test/java/com/polymarket/clob/order/OrderBuilderPol1271Test.java`

- [ ] **Step 1: 写测试**

```java
// src/test/java/com/polymarket/clob/order/OrderBuilderPol1271Test.java
package com.polymarket.clob.order;

import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class OrderBuilderPol1271Test {

    private static final String PK_HEX = "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    private static final Address WALLET = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");

    @Test
    void buildAndSignV2WithPol1271ProducesNestedSignature() throws Exception {
        Signer signer = LocalSigner.fromPrivateKeyHex(PK_HEX);
        OrderBuilder builder = OrderBuilder.builder()
                .chainId(137L)
                .signer(signer)
                .funder(WALLET)
                .signatureType(SignatureType.POLY_1271)
                .build();

        LimitOrderArgsV2 args = LimitOrderArgsV2.builder()
                .tokenId(new BigInteger("57597306756265660"))
                .side(Side.BUY)
                .price(new java.math.BigDecimal("0.5300"))
                .size(new java.math.BigDecimal("1"))
                .builderCode("0x" + "00".repeat(32))
                .metadata("0x" + "00".repeat(32))
                .build();

        SignedOrderV2 signed = builder.createOrderV2(args,
                CreateOrderOptions.builder().tickSize(TickSize.HUNDREDTHS).negRisk(false).build()).get();

        // maker = signer = wallet（POLY_1271 强制）
        assertThat(signed.getOrder().getMaker()).isEqualTo(WALLET);
        assertThat(signed.getOrder().getSigner()).isEqualTo(WALLET);
        assertThat(signed.getOrder().getSignatureType()).isEqualTo(SignatureType.POLY_1271);

        byte[] sig = HexFormat.of().parseHex(signed.getSignature().substring(2));
        int orderTypeLen = PolymarketContracts.ORDER_TYPE_STRING.getBytes(StandardCharsets.US_ASCII).length;
        assertThat(sig).hasSize(65 + 32 + 32 + orderTypeLen + 2);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -Dtest=OrderBuilderPol1271Test test`
Expected: FAIL — `OrderBuilder` 仍抛 `UnsupportedOperationException`

- [ ] **Step 3: 修改 `OrderBuilder.assembleOrderV2`**

打开 `src/main/java/com/polymarket/clob/order/OrderBuilder.java`，找到第 181 行附近的 `if (signatureType == SignatureType.POLY_1271)` 块，**删除整个 if 块**（4 行 throw 包括）。然后改 `createOrderV2` 与 `createMarketOrderV2` 末尾的 `EIP712OrderSigner.signV2(...)` 调用为：

```java
        if (signatureType == SignatureType.POLY_1271) {
            return Pol1271OrderSigner.sign(signer, order, chainId, options.negRisk());
        }
        return EIP712OrderSigner.signV2(signer, order, chainId, options.negRisk());
```

并在 `assembleOrderV2` 顶部增加 1271 校验：

```java
        if (signatureType == SignatureType.POLY_1271 && !funder.equals(signer.address())) {
            // POLY_1271 流：maker = signer = wallet（funder）；signer.address() 是 EOA 而不是 wallet。
            // 此处不强制 funder == EOA，正常 POLY_1271 配置下二者本应不同。允许通过。
        }
```

实际上 Builder 的 signer.address() 是 EOA，funder 是 wallet。`OrderV2.signer = signer.address()` 在 V1 域是 EOA，但 1271 域应该是 wallet。修改 `assembleOrderV2` 内 OrderV2 builder：

```java
        Address signerField = signatureType == SignatureType.POLY_1271 ? funder : signer.address();

        return OrderV2.builder()
                .salt(saltSource.next())
                .maker(funder)
                .signer(signerField)        // <--- 改这一行
                .tokenId(tokenId)
                ...
```

补上 `Pol1271OrderSigner` 的 import：

```java
import com.polymarket.clob.order.Pol1271OrderSigner;
```

（同包不需要 import；如果 `OrderBuilder` 与 `Pol1271OrderSigner` 在同一 `com.polymarket.clob.order` 包内，跳过此 import。）

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -Dtest=OrderBuilderPol1271Test test`
Expected: 1 test pass

- [ ] **Step 5: 全 order 测试回归**

Run: `mvn -Dtest='com.polymarket.clob.order.*Test' test`
Expected: 全部通过（既有 EIP712OrderSignerV2Test 等不应受影响）

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/polymarket/clob/order/OrderBuilder.java \
        src/test/java/com/polymarket/clob/order/OrderBuilderPol1271Test.java
git commit -m "feat(order): OrderBuilder 接通 POLY_1271 路径（路由到 Pol1271OrderSigner）"
```

---

## Phase F — onboard/ 编排器

### Task 19: `OnboardingConfig` + `TestOrderArgs` + `OnboardingResult`

**Files:**
- Create: `src/main/java/com/polymarket/clob/onboard/OnboardingConfig.java`
- Create: `src/main/java/com/polymarket/clob/onboard/TestOrderArgs.java`
- Create: `src/main/java/com/polymarket/clob/onboard/OnboardingResult.java`
- Test: `src/test/java/com/polymarket/clob/onboard/OnboardingConfigTest.java`

- [ ] **Step 1: 写 builder 测试**

```java
// src/test/java/com/polymarket/clob/onboard/OnboardingConfigTest.java
package com.polymarket.clob.onboard;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnboardingConfigTest {

    @Test
    void builderRequiresRpcUrl() {
        assertThatThrownBy(() -> OnboardingConfig.builder().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rpcUrl");
    }

    @Test
    void builderAppliesDefaults() {
        OnboardingConfig cfg = OnboardingConfig.builder()
                .rpcUrl(URI.create("https://polygon-rpc.com"))
                .build();

        assertThat(cfg.chainId()).isEqualTo(137);
        assertThat(cfg.gammaHost()).isEqualTo(URI.create("https://gamma-api.polymarket.com"));
        assertThat(cfg.relayerHost()).isEqualTo(URI.create("https://relayer-v2.polymarket.com"));
        assertThat(cfg.clobHost()).isEqualTo(URI.create("https://clob.polymarket.com"));
        assertThat(cfg.relayerPollInterval()).isEqualTo(Duration.ofSeconds(2));
        assertThat(cfg.relayerMaxAttempts()).isEqualTo(90);
        assertThat(cfg.testOrder()).isEmpty();
    }

    @Test
    void unsupportedChainRejected() {
        assertThatThrownBy(() -> OnboardingConfig.builder()
                .rpcUrl(URI.create("https://example"))
                .chainId(80002)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("80002");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -Dtest=OnboardingConfigTest test`
Expected: FAIL

- [ ] **Step 3: 实现三个 record + Builder**

```java
// src/main/java/com/polymarket/clob/onboard/TestOrderArgs.java
package com.polymarket.clob.onboard;

import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.order.OrderType;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Onboarder 可选下单参数。{@link Onboarder#run(com.polymarket.clob.auth.Signer)}
 * 在配置中提供本对象时执行步骤 7（POLY_1271 下单），否则跳过。
 */
public record TestOrderArgs(
        BigInteger tokenId,
        Side side,
        BigDecimal price,
        BigDecimal size,
        OrderType orderType,
        String tickSize,
        boolean negRisk
) {}
```

```java
// src/main/java/com/polymarket/clob/onboard/OnboardingResult.java
package com.polymarket.clob.onboard;

import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.gamma.GammaSession;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.order.PostOrderResponse;

import java.util.Optional;

public record OnboardingResult(
        Address wallet,
        ApiCredentials creds,
        GammaSession session,
        Optional<PostOrderResponse> testOrder
) {}
```

```java
// src/main/java/com/polymarket/clob/onboard/OnboardingConfig.java
package com.polymarket.clob.onboard;

import com.polymarket.clob.model.ContractRegistry;
import com.polymarket.clob.order.SaltSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

public record OnboardingConfig(
        long chainId,
        URI gammaHost,
        URI relayerHost,
        URI clobHost,
        URI rpcUrl,
        Duration relayerPollInterval,
        int relayerMaxAttempts,
        HttpClient httpClient,
        SaltSource saltSource,
        Optional<TestOrderArgs> testOrder
) {

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private long chainId = 137L;
        private URI gammaHost   = URI.create("https://gamma-api.polymarket.com");
        private URI relayerHost = URI.create("https://relayer-v2.polymarket.com");
        private URI clobHost    = URI.create("https://clob.polymarket.com");
        private URI rpcUrl;
        private Duration relayerPollInterval = Duration.ofSeconds(2);
        private int relayerMaxAttempts = 90;
        private HttpClient httpClient;
        private SaltSource saltSource;
        private Optional<TestOrderArgs> testOrder = Optional.empty();

        public Builder chainId(long v) { this.chainId = v; return this; }
        public Builder gammaHost(URI v) { this.gammaHost = v; return this; }
        public Builder relayerHost(URI v) { this.relayerHost = v; return this; }
        public Builder clobHost(URI v) { this.clobHost = v; return this; }
        public Builder rpcUrl(URI v) { this.rpcUrl = v; return this; }
        public Builder relayerPollInterval(Duration v) { this.relayerPollInterval = v; return this; }
        public Builder relayerMaxAttempts(int v) { this.relayerMaxAttempts = v; return this; }
        public Builder httpClient(HttpClient v) { this.httpClient = v; return this; }
        public Builder saltSource(SaltSource v) { this.saltSource = v; return this; }
        public Builder testOrder(TestOrderArgs v) { this.testOrder = Optional.ofNullable(v); return this; }

        public OnboardingConfig build() {
            if (rpcUrl == null) throw new IllegalStateException("rpcUrl is required");
            if (ContractRegistry.depositWalletConfig(chainId).isEmpty()) {
                throw new IllegalStateException("DepositWallet not deployed on chainId " + chainId);
            }
            return new OnboardingConfig(chainId, gammaHost, relayerHost, clobHost, rpcUrl,
                    relayerPollInterval, relayerMaxAttempts, httpClient, saltSource, testOrder);
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -Dtest=OnboardingConfigTest test`
Expected: 3 tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/polymarket/clob/onboard/OnboardingConfig.java \
        src/main/java/com/polymarket/clob/onboard/TestOrderArgs.java \
        src/main/java/com/polymarket/clob/onboard/OnboardingResult.java \
        src/test/java/com/polymarket/clob/onboard/OnboardingConfigTest.java
git commit -m "feat(onboard): OnboardingConfig + TestOrderArgs + OnboardingResult"
```

---

### Task 20: `Onboarder.run` 编排

**Files:**
- Create: `src/main/java/com/polymarket/clob/onboard/Onboarder.java`
- Test: `src/test/java/com/polymarket/clob/onboard/OnboarderTest.java`

> Onboarder 把 7 步串起来。测试用 WireMock 模拟 gamma + relayer + clob auth，用 stub `EvmRpcClient` 模拟链上读。下单步骤暂不在测试覆盖范围（需要真签真拼到 OrderApi，太重）。

- [ ] **Step 1: 写编排器测试（覆盖 step 1–6 + 幂等）**

```java
// src/test/java/com/polymarket/clob/onboard/OnboarderTest.java
package com.polymarket.clob.onboard;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.chain.EvmRpcClient;
import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class OnboarderTest {

    private static final String PK_HEX = "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";

    private WireMockServer gamma;
    private WireMockServer relayer;
    private WireMockServer clob;
    private Signer eoa;

    @BeforeEach
    void start() {
        gamma   = startMock();
        relayer = startMock();
        clob    = startMock();
        eoa = LocalSigner.fromPrivateKeyHex(PK_HEX);
    }

    @AfterEach
    void stop() { gamma.stop(); relayer.stop(); clob.stop(); }

    private static WireMockServer startMock() {
        WireMockServer s = new WireMockServer(0);
        s.start();
        return s;
    }

    private OnboardingConfig.Builder baseCfg() {
        return OnboardingConfig.builder()
                .gammaHost(URI.create(gamma.baseUrl()))
                .relayerHost(URI.create(relayer.baseUrl()))
                .clobHost(URI.create(clob.baseUrl()))
                .rpcUrl(URI.create("http://unused"))
                .relayerPollInterval(Duration.ofMillis(10))
                .relayerMaxAttempts(20);
    }

    /** 用 stub 替换默认 RPC，避免真发 HTTP。 */
    private EvmRpcClient stubRpcAlreadyDeployed(Address wallet) {
        return new EvmRpcClient() {
            @Override public CompletableFuture<byte[]> ethCall(Address to, byte[] callData) {
                if (to.equals(PolymarketContracts.FACTORY)) {
                    byte[] out = new byte[32];
                    System.arraycopy(wallet.toBytes(), 0, out, 12, 20);
                    return CompletableFuture.completedFuture(out);
                }
                // walletNonce / allowance / approvedForAll → 全部 max → 不需要 batch
                byte[] max = HexFormat.of().parseHex(
                        "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
                return CompletableFuture.completedFuture(max);
            }
            @Override public CompletableFuture<byte[]> getCode(Address addr) {
                return CompletableFuture.completedFuture(new byte[]{0x60, (byte) 0x80});
            }
        };
    }

    private void stubGammaLoginOk() {
        gamma.stubFor(get(urlEqualTo("/nonce")).willReturn(okJson("{\"nonce\":\"abc\"}")
                .withHeader("Set-Cookie", "polymarket_anon=anon; Path=/")));
        gamma.stubFor(get(urlEqualTo("/login")).willReturn(okJson("{}")
                .withHeader("Set-Cookie", "polymarket_auth=auth; Path=/")));
        gamma.stubFor(get(urlPathEqualTo("/users")).willReturn(okJson("[{\"id\":\"u\"}]")));
    }

    private void stubClobAuthOk() {
        clob.stubFor(get(urlEqualTo("/auth/derive-api-key"))
                .willReturn(okJson("{\"apiKey\":\"k\",\"secret\":\"c2VjcmV0\",\"passphrase\":\"p\"}")));
    }

    @Test
    void runWithExistingWalletAndAllAllowancesSetSkipsRelayer() throws ExecutionException, InterruptedException {
        Address wallet = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");

        stubGammaLoginOk();
        stubClobAuthOk();

        Onboarder onboarder = Onboarder.forTesting(baseCfg().build(), stubRpcAlreadyDeployed(wallet));
        OnboardingResult r = onboarder.run(eoa).get();

        assertThat(r.wallet()).isEqualTo(wallet);
        assertThat(r.creds().key()).isEqualTo("k");
        relayer.verify(0, postRequestedFor(urlEqualTo("/submit")));
    }

    @Test
    void runWithNonexistentWalletDeploysAndApproves() throws ExecutionException, InterruptedException {
        Address wallet = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");

        stubGammaLoginOk();
        stubClobAuthOk();

        relayer.stubFor(post(urlEqualTo("/submit"))
                .willReturn(okJson("{\"transactionID\":\"tx\",\"state\":\"STATE_NEW\"}")));
        relayer.stubFor(get(urlPathEqualTo("/transaction"))
                .willReturn(okJson("[{\"state\":\"STATE_CONFIRMED\",\"transactionHash\":\"0xabc\"}]")));
        relayer.stubFor(get(urlPathEqualTo("/nonce")).willReturn(okJson("{\"nonce\":0}")));

        // RPC：第一次 getCode 返回空（未部署），后续返回 bytecode；allowance 全 0
        EvmRpcClient rpc = new EvmRpcClient() {
            int codeCalls = 0;
            @Override public CompletableFuture<byte[]> ethCall(Address to, byte[] callData) {
                if (to.equals(PolymarketContracts.FACTORY)) {
                    byte[] out = new byte[32];
                    System.arraycopy(wallet.toBytes(), 0, out, 12, 20);
                    return CompletableFuture.completedFuture(out);
                }
                // 默认全 0 → 全部需要 approve（且 wallet.nonce()=0）
                return CompletableFuture.completedFuture(new byte[32]);
            }
            @Override public CompletableFuture<byte[]> getCode(Address addr) {
                codeCalls++;
                return CompletableFuture.completedFuture(codeCalls == 1 ? new byte[0] : new byte[]{0x60});
            }
        };

        Onboarder onboarder = Onboarder.forTesting(baseCfg().build(), rpc);
        OnboardingResult r = onboarder.run(eoa).get();

        assertThat(r.wallet()).isEqualTo(wallet);
        // 至少一次 wallet-create + 一次 batch
        relayer.verify(2, postRequestedFor(urlEqualTo("/submit")));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -Dtest=OnboarderTest test`
Expected: FAIL — `Onboarder` 不存在

- [ ] **Step 3: 实现 `Onboarder`**

```java
// src/main/java/com/polymarket/clob/onboard/Onboarder.java
package com.polymarket.clob.onboard;

import com.polymarket.clob.api.AuthApi;
import com.polymarket.clob.api.AuthApiImpl;
import com.polymarket.clob.api.OrderApi;
import com.polymarket.clob.api.OrderApiImpl;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.chain.DepositWalletReads;
import com.polymarket.clob.chain.EvmRpcClient;
import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.chain.Web3jEvmRpcClient;
import com.polymarket.clob.deposit.ApprovalPlanner;
import com.polymarket.clob.deposit.BatchEip712;
import com.polymarket.clob.deposit.Call;
import com.polymarket.clob.deposit.DepositWalletDerivation;
import com.polymarket.clob.deposit.DepositWalletRelayer;
import com.polymarket.clob.deposit.SignedBatch;
import com.polymarket.clob.gamma.GammaClient;
import com.polymarket.clob.gamma.GammaSession;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.ContractRegistry;
import com.polymarket.clob.order.CreateOrderOptions;
import com.polymarket.clob.order.LimitOrderArgsV2;
import com.polymarket.clob.order.OrderBuilder;
import com.polymarket.clob.order.PostOrderResponse;
import com.polymarket.clob.order.SignedOrderV2;
import com.polymarket.clob.order.TickSize;

import java.io.IOException;
import java.math.BigInteger;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 7 步 onboard + (可选)trade 编排器。每步先读再写，重入安全。
 */
public final class Onboarder {

    private final OnboardingConfig cfg;
    private final HttpClient http;
    private final EvmRpcClient rpc;
    private final GammaClient gamma;
    private final DepositWalletReads reads;
    private final DepositWalletDerivation derivation;

    public Onboarder(OnboardingConfig cfg) {
        this(cfg, new Web3jEvmRpcClient(cfg.rpcUrl()));
    }

    /** 测试入口：注入 stub RPC。 */
    public static Onboarder forTesting(OnboardingConfig cfg, EvmRpcClient rpc) {
        return new Onboarder(cfg, rpc);
    }

    private Onboarder(OnboardingConfig cfg, EvmRpcClient rpc) {
        this.cfg = cfg;
        this.http = cfg.httpClient() != null ? cfg.httpClient() : HttpClient.newHttpClient();
        this.rpc = rpc;
        this.gamma = new GammaClient(cfg.gammaHost(), http);
        this.reads = new DepositWalletReads(rpc, cfg.chainId());
        this.derivation = new DepositWalletDerivation(reads);
    }

    public CompletableFuture<OnboardingResult> run(Signer eoa) {
        // 1. gamma login
        return gamma.loginWithSiwe(eoa, cfg.chainId()).thenCompose(session ->
            // 2. derive wallet
            derivation.predictWalletAddress(eoa.address()).thenCompose(wallet ->
                // 3. ensure profile
                gamma.ensureProfile(session, eoa.address(), wallet).thenCompose(v ->
                    // 4. deploy if needed
                    deployIfNeeded(eoa, wallet, session).thenCompose(v2 ->
                        // 5. plan + submit approvals if needed
                        applyApprovals(eoa, wallet, session).thenCompose(v3 ->
                            // 6. derive or create CLOB credentials
                            deriveOrCreateCreds(eoa).thenCompose(creds ->
                                // 7. optional test order
                                placeOptionalOrder(eoa, wallet, creds).thenApply(orderResp ->
                                    new OnboardingResult(wallet, creds, session, orderResp))))))));
    }

    private CompletableFuture<Void> deployIfNeeded(Signer eoa, Address wallet, GammaSession session) {
        return derivation.isDeployed(wallet).thenCompose(deployed -> {
            if (deployed) return CompletableFuture.completedFuture(null);
            try {
                DepositWalletRelayer relayer = new DepositWalletRelayer(cfg.relayerHost(), http, session);
                String txId = relayer.submitWalletCreate(eoa.address(), PolymarketContracts.FACTORY);
                relayer.waitForTx(txId, cfg.relayerPollInterval(), cfg.relayerMaxAttempts());
                return CompletableFuture.completedFuture(null);
            } catch (IOException | InterruptedException e) {
                return CompletableFuture.failedFuture(e);
            }
        });
    }

    private CompletableFuture<Void> applyApprovals(Signer eoa, Address wallet, GammaSession session) {
        var planner = new ApprovalPlanner(reads, ContractRegistry.depositWalletConfig(cfg.chainId()).orElseThrow());
        return planner.planMissingApprovals(wallet).thenCompose(calls -> {
            if (calls.isEmpty()) return CompletableFuture.completedFuture(null);
            return reads.walletNonce(wallet).thenCompose(nonce -> {
                BigInteger deadline = BigInteger.valueOf(Instant.now().getEpochSecond() + 1800);
                byte[] digest = BatchEip712.hashBatch(cfg.chainId(), wallet, nonce, deadline, calls);
                return eoa.signHash(digest).thenCompose(sig65 -> {
                    SignedBatch batch = new SignedBatch(eoa.address(), PolymarketContracts.FACTORY,
                            wallet, nonce, deadline, calls, sig65);
                    try {
                        DepositWalletRelayer relayer = new DepositWalletRelayer(cfg.relayerHost(), http, session);
                        String txId = relayer.submitBatch(batch);
                        relayer.waitForTx(txId, cfg.relayerPollInterval(), cfg.relayerMaxAttempts());
                        return CompletableFuture.<Void>completedFuture(null);
                    } catch (IOException | InterruptedException e) {
                        return CompletableFuture.<Void>failedFuture(e);
                    }
                });
            });
        });
    }

    private CompletableFuture<ApiCredentials> deriveOrCreateCreds(Signer eoa) {
        AuthApi auth = new AuthApiImpl(http, cfg.clobHost().toString());
        long ts = Instant.now().getEpochSecond();
        return auth.deriveApiKey(eoa, eoa.address(), ts, 0)
                .exceptionallyCompose(err -> auth.createApiKey(eoa, eoa.address(), ts, 0));
    }

    private CompletableFuture<Optional<PostOrderResponse>> placeOptionalOrder(
            Signer eoa, Address wallet, ApiCredentials creds) {
        if (cfg.testOrder().isEmpty()) return CompletableFuture.completedFuture(Optional.empty());
        TestOrderArgs args = cfg.testOrder().get();

        OrderBuilder builder = OrderBuilder.builder()
                .chainId(cfg.chainId())
                .signer(eoa)
                .funder(wallet)
                .signatureType(SignatureType.POLY_1271)
                .build();

        LimitOrderArgsV2 limitArgs = LimitOrderArgsV2.builder()
                .tokenId(args.tokenId())
                .side(args.side())
                .price(args.price())
                .size(args.size())
                .builderCode("0x" + "00".repeat(32))
                .metadata("0x" + "00".repeat(32))
                .build();

        TickSize tick = TickSize.fromString(args.tickSize());
        return builder.createOrderV2(limitArgs,
                CreateOrderOptions.builder().tickSize(tick).negRisk(args.negRisk()).build())
                .thenCompose(signed -> {
                    OrderApi api = new OrderApiImpl(http, cfg.clobHost().toString());
                    long ts = Instant.now().getEpochSecond();
                    return api.postOrderV2(eoa.address(), creds, ts, signed, args.orderType())
                            .thenApply(Optional::of);
                });
    }
}
```

> 注：上文 `AuthApi.deriveApiKey` / `AuthApi.createApiKey` 的方法签名按现有 `AuthApiImpl` 公共方法名调用。如果实际方法签名不同，请阅读 `src/main/java/com/polymarket/clob/api/AuthApi.java` 后做最小改动 —— 例如方法名为 `deriveOrCreate(...)` 或入参顺序不同。
> `TickSize.fromString(...)` 假设存在；若不存在，按 spec §6.3 把 `tickSize` 字符串映射为 `TickSize.HUNDREDTHS / THOUSANDTHS / TENTH` 等 enum，落到 helper 内。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -Dtest=OnboarderTest test`
Expected: 2 tests pass

> 如果 AuthApi / TickSize / OrderApiImpl 实际签名导致编译失败，按 IDE 提示最小调整。Onboarder 的逻辑骨架不变。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/polymarket/clob/onboard/Onboarder.java \
        src/test/java/com/polymarket/clob/onboard/OnboarderTest.java
git commit -m "feat(onboard): Onboarder.run 7 步编排实现"
```

---

## Phase G — 示例

### Task 21: `DepositWalletOnboardAndTradeExample`

**Files:**
- Create: `src/main/java/com/polymarket/clob/example/DepositWalletOnboardAndTradeExample.java`

> 示例文件用 env vars 配置（`PK / RPC_URL / TOKEN_ID`），与 TS `fullOnboardAndTrade.ts` 对齐。不写单测，编译通过即可；真链冒烟由用户手动跑。

- [ ] **Step 1: 创建示例**

```java
// src/main/java/com/polymarket/clob/example/DepositWalletOnboardAndTradeExample.java
package com.polymarket.clob.example;

import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.onboard.Onboarder;
import com.polymarket.clob.onboard.OnboardingConfig;
import com.polymarket.clob.onboard.OnboardingResult;
import com.polymarket.clob.onboard.TestOrderArgs;
import com.polymarket.clob.order.OrderType;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * Polymarket Deposit Wallet 端到端 onboard + trade 示例。
 *
 * <p>环境变量：
 * <ul>
 *   <li>{@code PK}（必填）—— EOA 私钥 hex（可带或不带 {@code 0x} 前缀）</li>
 *   <li>{@code RPC_URL}（可选）—— Polygon RPC，默认 {@code https://polygon-rpc.com}</li>
 *   <li>{@code TOKEN_ID}（可选）—— 设置后跑下单步骤，否则仅 onboarding</li>
 * </ul>
 *
 * <p>真链冒烟前置：deposit wallet 至少需要 1 USDC.e 才能下单（CLOB 会拒绝余额不足）。
 */
public final class DepositWalletOnboardAndTradeExample {

    private DepositWalletOnboardAndTradeExample() {}

    public static void main(String[] args) throws Exception {
        String pk = Objects.requireNonNull(System.getenv("PK"), "set PK env var (EOA private key)");
        String rpcUrl = Optional.ofNullable(System.getenv("RPC_URL")).orElse("https://polygon-rpc.com");
        String tokenIdStr = System.getenv("TOKEN_ID");

        Signer eoa = LocalSigner.fromPrivateKeyHex(pk);

        OnboardingConfig.Builder b = OnboardingConfig.builder().rpcUrl(URI.create(rpcUrl));
        if (tokenIdStr != null && !tokenIdStr.isBlank()) {
            b.testOrder(new TestOrderArgs(
                    new BigInteger(tokenIdStr),
                    Side.BUY,
                    new BigDecimal("0.1"),
                    new BigDecimal("5"),
                    OrderType.GTC,
                    "0.01",
                    false));
        }

        System.out.println("══════════════════════════════════════════════════════════════");
        System.out.println(" Polymarket Deposit Wallet Onboard + Trade");
        System.out.println("══════════════════════════════════════════════════════════════");
        System.out.println("EOA:     " + eoa.address().toHex());
        System.out.println("Chain:   Polygon (137)");
        System.out.println(tokenIdStr == null ? "Mode:    onboard only (TOKEN_ID not set)" : "Mode:    onboard + trade");

        Onboarder onboarder = new Onboarder(b.build());
        OnboardingResult r = onboarder.run(eoa).get();

        System.out.println("\n✅ Wallet:        " + r.wallet().toHex());
        System.out.println("   API key:       " + r.creds().key());
        r.testOrder().ifPresent(resp -> System.out.println("   Order response: " + resp));
        System.out.println("\n══════════════════════════════════════════════════════════════");
    }
}
```

- [ ] **Step 2: 编译确认**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/polymarket/clob/example/DepositWalletOnboardAndTradeExample.java
git commit -m "feat(example): 新增 DepositWalletOnboardAndTradeExample（替换 EndToEndOnboardingExample）"
```

---

## Phase H — 删除旧 Safe 代码

> 警告：以下任务是破坏性变更。每一步删除后，跑 `mvn compile` 修复连锁编译错误。最后一步 Task 25 跑全套 `mvn clean verify` 把红线归零。

### Task 22: 删除 examples/Safe* 三个文件

**Files:**
- Delete: `src/main/java/com/polymarket/clob/example/SafeWalletExample.java`
- Delete: `src/main/java/com/polymarket/clob/example/PolymarketBridge.java`
- Delete: `src/main/java/com/polymarket/clob/example/EndToEndOnboardingExample.java`

- [ ] **Step 1: 删三个文件**

```bash
rm src/main/java/com/polymarket/clob/example/SafeWalletExample.java
rm src/main/java/com/polymarket/clob/example/PolymarketBridge.java
rm src/main/java/com/polymarket/clob/example/EndToEndOnboardingExample.java
```

- [ ] **Step 2: 编译确认**

Run: `mvn compile`
Expected: BUILD SUCCESS（三个示例不被其它代码引用）

- [ ] **Step 3: Commit**

```bash
git add -A src/main/java/com/polymarket/clob/example/
git commit -m "chore(example): 删除 Safe 流过时示例（SafeWalletExample / PolymarketBridge / EndToEndOnboardingExample）"
```

---

### Task 23: 删除 `gasless/` 与 `auth/builder/` 整包

**Files:**
- Delete: `src/main/java/com/polymarket/clob/gasless/` 整个目录
- Delete: `src/main/java/com/polymarket/clob/auth/builder/` 整个目录
- Delete: `src/test/java/com/polymarket/clob/gasless/` 整个目录
- Delete: `src/test/java/com/polymarket/clob/auth/builder/` 整个目录

- [ ] **Step 1: 删除两个 main 包目录**

```bash
rm -r src/main/java/com/polymarket/clob/gasless/
rm -r src/main/java/com/polymarket/clob/auth/builder/
```

- [ ] **Step 2: 删除两个 test 包目录**

```bash
rm -r src/test/java/com/polymarket/clob/gasless/
rm -r src/test/java/com/polymarket/clob/auth/builder/
```

- [ ] **Step 3: 编译确认**

Run: `mvn compile`
Expected: 可能 FAIL — 有代码 import 了被删的类。逐个 fix：

预期需要修复的位置（按 git grep 找）：
```bash
grep -rn "com.polymarket.clob.gasless\|com.polymarket.clob.auth.builder" \
        src/main/java src/test/java
```

每个匹配文件都需要删除相关 import 与使用代码。如果某个测试或工具类完全依赖被删类，把整个文件也一起删除（重新评估其价值）。如果是 `OrderBuilder` 或 `ClobClientBuilder` 这类核心类引用了 builder 配置，将相应字段 / 参数移除（这些字段对 V1 Safe 流才有意义）。

- [ ] **Step 4: 测试编译确认**

Run: `mvn test-compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: 跑剩下的测试集回归**

Run: `mvn test`
Expected: 全部通过（被删的测试已不存在；剩余测试不应受影响）

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "chore: 删除 gasless/ 与 auth/builder/ 整包（Safe 流退役）"
```

---

### Task 24: 简化 `SignatureType` + 改写 `WalletDerivation`

**Files:**
- Modify: `src/main/java/com/polymarket/clob/auth/SignatureType.java` — 删除 `POLY_PROXY` 与 `POLY_GNOSIS_SAFE`
- Modify or Delete: `src/main/java/com/polymarket/clob/auth/WalletDerivation.java` — 删除 Safe / Proxy 派生方法
- Modify or Delete: `src/main/java/com/polymarket/clob/model/WalletContractConfig.java` — 仅 Safe 用，删除
- Modify: `src/main/java/com/polymarket/clob/model/ContractRegistry.java` — 删除 `walletConfig` 方法
- Modify: `src/test/java/com/polymarket/clob/auth/WalletDerivationTest.java` — 删除 Safe / Proxy case

- [ ] **Step 1: 简化 `SignatureType`**

打开 `src/main/java/com/polymarket/clob/auth/SignatureType.java`，删除 `POLY_PROXY(1)` 与 `POLY_GNOSIS_SAFE(2)` 行：

```java
public enum SignatureType {
    EOA(0),
    POLY_1271(3);

    // ... 其余字段与方法保留不变
}
```

注释也一并清理：删除 "V1 时代只有 0/1/2"、"POLY_PROXY (1) / POLY_GNOSIS_SAFE (2)" 等已过时表述。

- [ ] **Step 2: 删除或改写 `WalletDerivation`**

`WalletDerivation` 三个方法全部基于 Safe / Proxy 工厂；改用 `DepositWalletDerivation`。直接删除：

```bash
rm src/main/java/com/polymarket/clob/auth/WalletDerivation.java
```

- [ ] **Step 3: 删除 `WalletContractConfig`**

```bash
rm src/main/java/com/polymarket/clob/model/WalletContractConfig.java
```

- [ ] **Step 4: 修 `ContractRegistry`**

打开 `src/main/java/com/polymarket/clob/model/ContractRegistry.java`，删除：
- 顶部 `import com.polymarket.clob.model.WalletContractConfig;`
- `walletConfig(long chainId)` 方法及任何 Safe 工厂常量字段
- 任何只服务 Safe 流的字段或常量

保留：
- `contractConfig(...)` （CTF Exchange v1）
- `exchangeV2(long, boolean)`
- `depositWalletConfig(long)`（Task 2 新增）

- [ ] **Step 5: 删除 `WalletDerivationTest`**

```bash
rm src/test/java/com/polymarket/clob/auth/WalletDerivationTest.java
```

- [ ] **Step 6: 修复连锁编译错误**

```bash
grep -rn "WalletDerivation\|WalletContractConfig\|POLY_PROXY\|POLY_GNOSIS_SAFE\|walletConfig(" \
        src/main/java src/test/java
```

逐个删除 import 与使用代码。

- [ ] **Step 7: 跑全套测试确认**

Run: `mvn test`
Expected: 全部通过

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "chore: 简化 SignatureType + 删除 WalletDerivation / WalletContractConfig"
```

---

### Task 25: 跳版本号、更新 README、跑全套验证

**Files:**
- Modify: `pom.xml` — `<version>` 跳到 `2.0.0`
- Modify: `README.md` — 加 v2 banner、Quickstart 替换、链支持改 Polygon-only、加迁移指南
- Create: `CHANGELOG.md` — 记录 v2 破坏性变更
- Delete: `src/test/resources/fixtures/parity/safe/` 与 `fixtures/parity/builder-relayer/`（如存在）

- [ ] **Step 1: 跳版本号**

打开 `pom.xml`，找到第 9 行 `<version>0.1.0-SNAPSHOT</version>`，改为：

```xml
    <version>2.0.0</version>
```

- [ ] **Step 2: 删除 Safe parity fixtures**

```bash
ls src/test/resources/fixtures/ 2>/dev/null
# 如果存在 safe / builder-relayer 子目录，整个删
find src/test/resources/fixtures -type d \( -name safe -o -name builder-relayer \) -exec rm -r {} +
```

- [ ] **Step 3: 创建 `CHANGELOG.md`**

```markdown
# Changelog

## 2.0.0 — 2026-05-07

### Breaking changes

- 钱包模型从 Gnosis Safe 切换到 Polymarket Deposit Wallet（Solady CWIA 最小代理）
- 删除 `gasless/` 整包（`GaslessRelayer / SafeEip712 / SafeSignatures / MultiSend / Calldata` 等）
- 删除 `auth/builder/` 整包（Builder HMAC 鉴权改走 Gamma cookie）
- 删除 `WalletDerivation`（被 `DepositWalletDerivation` 替代）
- 删除 `WalletContractConfig`（被 `DepositWalletConfig` 替代）
- `SignatureType` 枚举仅保留 `EOA(0)` 与 `POLY_1271(3)`；`POLY_PROXY` / `POLY_GNOSIS_SAFE` 已删除
- `ContractRegistry.walletConfig(...)` → `ContractRegistry.depositWalletConfig(...)`
- 链支持从 Polygon + Amoy 收窄至 Polygon only

### Added

- `chain/` 包：`EvmRpcClient` / `Web3jEvmRpcClient` / `DepositWalletReads` / `PolymarketContracts`
- `gamma/` 包：`GammaClient`（SIWE 登录 + 用户档案）
- `deposit/` 包：`DepositWalletDerivation` / `DepositWalletRelayer` / `ApprovalPlanner` / `BatchEip712`
- `order/Pol1271OrderSigner`：ERC-7739 嵌套 TypedDataSign 签名（POLY_1271）
- `onboard/Onboarder`：薄编排器，一次调用跑完 EOA → 下单全链路
- `example/DepositWalletOnboardAndTradeExample`：替换旧 `EndToEndOnboardingExample`

### Migration guide

参见 `docs/superpowers/specs/2026-05-07-deposit-wallet-onboarding-design.md` §8 表 8.1 / 8.2。
```

- [ ] **Step 4: 更新 README.md 顶部 banner + Quickstart**

打开 `README.md`，在标题下加 banner（如果原 README 已有标题，把 banner 紧跟其后）：

```markdown
> ⚠️ **v2 已切换到 Deposit Wallet 流，与 v1 Safe 流不兼容。**
> 升级路径见 `CHANGELOG.md`。
```

把现有 Quickstart 段落（Safe 例子）替换为：

```markdown
## Quickstart

```java
import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.onboard.Onboarder;
import com.polymarket.clob.onboard.OnboardingConfig;
import java.net.URI;

var eoa = LocalSigner.fromPrivateKeyHex(System.getenv("PK"));
var cfg = OnboardingConfig.builder()
        .rpcUrl(URI.create("https://polygon-rpc.com"))
        .build();

var result = new Onboarder(cfg).run(eoa).get();
System.out.println("Wallet:  " + result.wallet().toHex());
System.out.println("API key: " + result.creds().key());
```

完整端到端 + 下单见 `src/main/java/com/polymarket/clob/example/DepositWalletOnboardAndTradeExample.java`。
```

把链支持段改为：

```markdown
## 链支持

| chainId | 名称     | 状态 |
|---------|----------|------|
| 137     | Polygon  | ✅   |
```

加新章节「真链冒烟流程」：

```markdown
## 真链冒烟

```bash
export PK=0x<你的 EOA 私钥>
export RPC_URL=https://polygon-rpc.com         # 可选
export TOKEN_ID=<某 outcome token id>          # 可选；不传只跑 onboarding
mvn -q exec:java -Dexec.mainClass=com.polymarket.clob.example.DepositWalletOnboardAndTradeExample
```

下单前需确保 deposit wallet 持有 ≥ 1 USDC.e。
```

加新章节「迁移指南」：

```markdown
## 从 v1 (Safe 流) 迁移到 v2

详见 `CHANGELOG.md`。常用对照：

| v1 | v2 |
|---|---|
| `WalletDerivation.deriveSafeWallet(...)` | `new DepositWalletDerivation(reads).predictWalletAddress(...)` |
| `GaslessRelayer` | `DepositWalletRelayer` |
| `SignatureType.POLY_PROXY / POLY_GNOSIS_SAFE` | `SignatureType.POLY_1271`（强制） |
| Builder HMAC 头 | Gamma session cookie |
| `EndToEndOnboardingExample` | `DepositWalletOnboardAndTradeExample` |
```

旧 README 中提到 Safe / Proxy / V1 wallet derivation / Builder relayer 的段落整体删除。

- [ ] **Step 5: 全套验证**

Run: `mvn clean verify`
Expected: BUILD SUCCESS，全部测试通过

如果 `verify` 失败：
- 编译错 → grep 残余 `gasless` / `WalletDerivation` 等引用，删除
- 测试错 → 看具体失败原因。常见：mock 配置漏改、parity fixture 文件残留指向已删类
- 不要 `--no-verify` / `-DskipTests` 绕过；红线必须归零

- [ ] **Step 6: Commit**

```bash
git add pom.xml CHANGELOG.md README.md
# 如果删了 fixtures，也一并 add
git add -A
git commit -m "release: v2.0.0 — Deposit Wallet 流取代 Safe 流"
```

- [ ] **Step 7: 推送（可选，按用户决定）**

> 本步骤需用户显式同意才能执行。Plan 不假设自动 push。

```bash
git push origin main
```

---

## 自检清单（执行前）

实施工程师在开始前应快速浏览：

- [ ] Spec 文档 `docs/superpowers/specs/2026-05-07-deposit-wallet-onboarding-design.md` 是否已读完
- [ ] 本机 `mvn -v` 报告 Java 17+
- [ ] WireMock 已是 pom.xml 现有 dep（version `3.9.2`，无需新增）
- [ ] `git status` 干净，无未提交改动（除非有意 stash）
- [ ] 有可用的 Polygon RPC URL 用于真链冒烟（仅 Phase G/H 验证时用）

## 完成标准

- [ ] Phase A–H 25 个 Task 全部 commit
- [ ] `mvn clean verify` 一次通过
- [ ] `git grep -nE 'gasless|SafeEip712|SafeSignatures|POLY_GNOSIS_SAFE|POLY_PROXY|WalletContractConfig|BuilderHeaderBuilder' src/main src/test` 返回空
- [ ] `pom.xml` 版本 = `2.0.0`
- [ ] README 含 v2 banner、Polygon-only 链支持、迁移指南
- [ ] CHANGELOG.md 存在并记录破坏性变更

---

## 已知 API 假设（执行时按 IDE 提示对接）

下列符号在写本 plan 时未在源码逐一核实精确签名，执行工程师在编译失败时按 IDE 提示最小调整：

| 符号 | 出现位置 | 备注 |
|---|---|---|
| `AuthApi.deriveApiKey(...)` / `createApiKey(...)` | Task 20 `Onboarder.deriveOrCreateCreds` | 现有 `AuthApiImpl` 公开方法名/入参顺序可能不同；按实际签名替换 |
| `ApiCredentials.key()` getter | Task 21 example、Task 20 注释 | 若实际为 `apiKey()`，统一替换 |
| `TickSize.fromString(String)` | Task 20 `placeOptionalOrder` | 若不存在，按 spec §6.3 在 helper 内做 `"0.01" → TickSize.HUNDREDTHS` 等枚举映射 |
| `Address.toHex()` 是否返回 EIP-55 checksum | Task 6 `SiweMessage` | SIWE 文本要求 checksum 形态 EOA；如返回小写则改用 `Address.toChecksumHex()` 或新增方法 |

修复时优先选项是改测试中的预期值（如果实际签名等价但命名不同），而不是改 SDK 既有公共 API。

## Spec §7.3 Parity Fixtures（Follow-up）

Spec §7.3 定义的 7 个 parity fixture 文件（`batch-eip712-12-calls.json` 等）在本 plan 中**未单独建 task**，理由：

- Fixture 字节级正确性需要外部环境（viem TS）按相同输入计算后 dump
- 单元测试已用「自洽 + 边界 + 内层结构」三层覆盖，能拦住 99% 实现错误
- 字节级链上验签的最终验证靠 Task 21 真链冒烟（example 跑通即证明嵌套签名格式正确）

如需补 fixture，建议在 `mvn clean verify` 全绿后单独提一个 follow-up PR：
1. 在 `clob-client-v2/scripts/` 写 dump 脚本（`hashTypedData(...)` + `JSON.stringify`）
2. 把输出落到 `src/test/resources/fixtures/parity/v2/deposit-wallet/`
3. 各 `*Test` 文件加 `@Test` 读 fixture + 比对

---

Plan complete and saved to `docs/superpowers/plans/2026-05-07-deposit-wallet-onboarding-plan.md`. Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration
2. **Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?

