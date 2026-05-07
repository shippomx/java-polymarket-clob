# Fun.xyz On-Ramp v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `com.polymarket:clob-client:2.0.0` 中新增独立子包 `com.polymarket.clob.funxyz/`，把 `POST https://api.fun.xyz/v1/eoa` 封装成单方法 `FunxyzClient.getDepositAddresses(eoa, recipient) → CompletableFuture<DepositAddresses>`。

**Architecture:** 5 个 final 类（`FunxyzClient` / `FunxyzConfig` / `DepositAddresses` / `FunxyzException` / `package-info`），全部位于一个新包内，零依赖 `chain/ deposit/ onboard/`。HTTP 走 `java.net.http.HttpClient.sendAsync`，JSON 走项目共享 `JsonCodec.objectMapper()`，错误统一收敛到 `FunxyzException`。镜像 `gamma/GammaClient` 的实现风格。

**Tech Stack:** Java 17, Jackson (via `com.polymarket.clob.http.JsonCodec`), `java.net.http.HttpClient`, JUnit Jupiter 5, AssertJ, WireMock 3.9.2 (test scope, 已在 pom)，SLF4J。

**Spec:** `docs/superpowers/specs/2026-05-07-funxyz-onramp-v2-design.md`

---

## File Structure

| 文件 | 创建/修改 | 职责 |
|---|---|---|
| `src/main/java/com/polymarket/clob/funxyz/FunxyzException.java` | 创建 | 唯一异常类型；带 `httpStatus`、`isTransport()`、`isBlocked()` |
| `src/main/java/com/polymarket/clob/funxyz/DepositAddresses.java` | 创建 | record(`Address evm`, `String solana`, `String tron`, `String btcSegwit`) |
| `src/main/java/com/polymarket/clob/funxyz/FunxyzConfig.java` | 创建 | record + Builder，4 个字段全部带默认值 |
| `src/main/java/com/polymarket/clob/funxyz/FunxyzClient.java` | 创建 | 主类，构造请求体 + 4 个固定头 + 异步发起 + 响应解析 |
| `src/main/java/com/polymarket/clob/funxyz/package-info.java` | 创建 | 包级 javadoc |
| `src/test/java/com/polymarket/clob/funxyz/FunxyzExceptionTest.java` | 创建 | 异常字段/谓词单测 |
| `src/test/java/com/polymarket/clob/funxyz/FunxyzConfigTest.java` | 创建 | Builder 默认值单测 |
| `src/test/java/com/polymarket/clob/funxyz/FunxyzClientTest.java` | 创建 | WireMock + 8 个用例 |
| `src/test/java/com/polymarket/clob/funxyz/FunxyzClientIntegrationTest.java` | 创建 | env-gated，打真实 fun.xyz |
| `src/main/java/com/polymarket/clob/example/FunxyzAddressLookupExample.java` | 创建 | 端到端 demo |
| `README.md` | 修改 | 末尾追加 `### Fun.xyz 法币入金地址（v2）` |
| `CHANGELOG.md` | 修改 | `[Unreleased]` 下添加一行 |

---

## Task 1: `FunxyzException` 类与单测

**Files:**
- Create: `src/main/java/com/polymarket/clob/funxyz/FunxyzException.java`
- Test: `src/test/java/com/polymarket/clob/funxyz/FunxyzExceptionTest.java`

- [ ] **Step 1: 写失败的测试**

```java
// src/test/java/com/polymarket/clob/funxyz/FunxyzExceptionTest.java
package com.polymarket.clob.funxyz;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FunxyzExceptionTest {

    @Test
    void httpStatusIsExposed() {
        FunxyzException ex = new FunxyzException("boom", 401);
        assertThat(ex.httpStatus()).isEqualTo(401);
        assertThat(ex.getMessage()).isEqualTo("boom");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void causeIsExposed() {
        Throwable cause = new RuntimeException("io");
        FunxyzException ex = new FunxyzException("wrap", -1, cause);
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.httpStatus()).isEqualTo(-1);
    }

    @Test
    void isTransportTrueWhenStatusNegative() {
        assertThat(new FunxyzException("io", -1).isTransport()).isTrue();
        assertThat(new FunxyzException("ok", 200).isTransport()).isFalse();
        assertThat(new FunxyzException("4xx", 401).isTransport()).isFalse();
    }

    @Test
    void isBlockedTrueOnlyForCanonicalMessage() {
        assertThat(new FunxyzException("eoa blocked by fun.xyz", 200).isBlocked()).isTrue();
        assertThat(new FunxyzException("something else", 200).isBlocked()).isFalse();
        assertThat(new FunxyzException("eoa blocked by fun.xyz", -1).isBlocked()).isTrue();  // message-driven
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `cd /Users/bmtaka/Downloads/java-polymarket-clob && mvn -o test -Dtest=FunxyzExceptionTest -q`
Expected: 编译失败，`cannot find symbol: class FunxyzException`。

- [ ] **Step 3: 实现 `FunxyzException`**

```java
// src/main/java/com/polymarket/clob/funxyz/FunxyzException.java
package com.polymarket.clob.funxyz;

/**
 * fun.xyz 调用失败统一异常类型。
 *
 * <p>{@link #httpStatus()} 语义：
 * <ul>
 *   <li>{@code -1}：传输失败（IO / TLS / DNS / timeout）</li>
 *   <li>正数：HTTP 状态码（包含 200 + 业务错，例如 {@code blocked == true} 或 schema 不合法）</li>
 * </ul>
 */
public final class FunxyzException extends RuntimeException {

    static final String BLOCKED_MESSAGE = "eoa blocked by fun.xyz";

    private final int httpStatus;

    public FunxyzException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public FunxyzException(String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public boolean isTransport() {
        return httpStatus < 0;
    }

    public boolean isBlocked() {
        return BLOCKED_MESSAGE.equals(getMessage());
    }
}
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `cd /Users/bmtaka/Downloads/java-polymarket-clob && mvn -o test -Dtest=FunxyzExceptionTest -q`
Expected: `BUILD SUCCESS`，4 用例全过。

- [ ] **Step 5: 提交**

```bash
cd /Users/bmtaka/Downloads/java-polymarket-clob
git add src/main/java/com/polymarket/clob/funxyz/FunxyzException.java \
        src/test/java/com/polymarket/clob/funxyz/FunxyzExceptionTest.java
git commit -m "feat(funxyz): FunxyzException 异常类型

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `DepositAddresses` record

**Files:**
- Create: `src/main/java/com/polymarket/clob/funxyz/DepositAddresses.java`

> 纯数据 record。无独立测试 —— `FunxyzClientTest` 的 happy path 用例会覆盖。项目里其它纯 record（如 `OnboardingResult`、`SignedBatch`）也都没单独测试。

- [ ] **Step 1: 写文件**

```java
// src/main/java/com/polymarket/clob/funxyz/DepositAddresses.java
package com.polymarket.clob.funxyz;

import com.polymarket.clob.model.Address;

/**
 * fun.xyz 给某个 EOA 在四条链上的固定入金中转地址。
 *
 * <p>同一 {@code eoa} 多次调用 fun.xyz 会得到相同的四个地址（服务端持久映射）。</p>
 *
 * <p>仅 EVM 地址使用项目自带 {@link Address} 类型；其它三条链项目无 value object，
 * 保留服务端原始字符串。调用方按需校验/转换。</p>
 *
 * @param evm        Polygon (chainId=137) 上的入金 EOA
 * @param solana     Solana base58 地址
 * @param tron       Tron base58 地址（"T..."）
 * @param btcSegwit  Bitcoin segwit 地址（"bc1q..."）
 */
public record DepositAddresses(
        Address evm,
        String solana,
        String tron,
        String btcSegwit
) {}
```

- [ ] **Step 2: 编译**

Run: `cd /Users/bmtaka/Downloads/java-polymarket-clob && mvn -o compile -q`
Expected: `BUILD SUCCESS`。

- [ ] **Step 3: 提交**

```bash
cd /Users/bmtaka/Downloads/java-polymarket-clob
git add src/main/java/com/polymarket/clob/funxyz/DepositAddresses.java
git commit -m "feat(funxyz): DepositAddresses record

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: `FunxyzConfig` 与默认值单测

**Files:**
- Create: `src/main/java/com/polymarket/clob/funxyz/FunxyzConfig.java`
- Test: `src/test/java/com/polymarket/clob/funxyz/FunxyzConfigTest.java`

- [ ] **Step 1: 写失败的测试**

```java
// src/test/java/com/polymarket/clob/funxyz/FunxyzConfigTest.java
package com.polymarket.clob.funxyz;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class FunxyzConfigTest {

    @Test
    void buildExposesAllDefaults() {
        FunxyzConfig cfg = FunxyzConfig.builder().build();

        assertThat(cfg.baseUrl()).isEqualTo(URI.create("https://api.fun.xyz"));
        assertThat(cfg.apiKey()).isEqualTo("Y53dikxXdT4E3afI1l8BMBSWgyhKvf65k6Dut1k6");
        assertThat(cfg.httpClient()).isNotNull();
        assertThat(cfg.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void overridesAreHonored() {
        URI customUrl = URI.create("http://localhost:18080");
        HttpClient customHttp = HttpClient.newHttpClient();
        Duration customTimeout = Duration.ofSeconds(3);

        FunxyzConfig cfg = FunxyzConfig.builder()
                .baseUrl(customUrl)
                .apiKey("my-key")
                .httpClient(customHttp)
                .requestTimeout(customTimeout)
                .build();

        assertThat(cfg.baseUrl()).isSameAs(customUrl);
        assertThat(cfg.apiKey()).isEqualTo("my-key");
        assertThat(cfg.httpClient()).isSameAs(customHttp);
        assertThat(cfg.requestTimeout()).isEqualTo(customTimeout);
    }

    @Test
    void publicConstantsExposed() {
        assertThat(FunxyzConfig.DEFAULT_PUBLIC_API_KEY)
                .isEqualTo("Y53dikxXdT4E3afI1l8BMBSWgyhKvf65k6Dut1k6");
        assertThat(FunxyzConfig.DEFAULT_BASE_URL)
                .isEqualTo(URI.create("https://api.fun.xyz"));
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `cd /Users/bmtaka/Downloads/java-polymarket-clob && mvn -o test -Dtest=FunxyzConfigTest -q`
Expected: 编译失败 `cannot find symbol: class FunxyzConfig`。

- [ ] **Step 3: 实现 `FunxyzConfig`**

```java
// src/main/java/com/polymarket/clob/funxyz/FunxyzConfig.java
package com.polymarket.clob.funxyz;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * {@link FunxyzClient} 的不可变配置。所有字段均带默认值。
 *
 * <p>{@code apiKey} 默认值是 Polymarket 前端硬编码的 fun.xyz public key（2026-05-07 抓包），
 * 适合开箱即用。如需替换成自有 fun.xyz 账号 key，用 {@link Builder#apiKey(String)} 覆盖。</p>
 */
public record FunxyzConfig(
        URI baseUrl,
        String apiKey,
        HttpClient httpClient,
        Duration requestTimeout
) {

    /** 默认 fun.xyz API 入口。 */
    public static final URI DEFAULT_BASE_URL = URI.create("https://api.fun.xyz");

    /**
     * Polymarket 前端公开 fun.xyz key（HAR 反向工程获得，非用户私密）。
     * 来源：{@code https://polymarket.com/_next/static/chunks/*.js} 中硬编码字面量。
     * 如失效，重新抓包替换或调用方传入自己的 key。
     */
    public static final String DEFAULT_PUBLIC_API_KEY = "Y53dikxXdT4E3afI1l8BMBSWgyhKvf65k6Dut1k6";

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private URI baseUrl = DEFAULT_BASE_URL;
        private String apiKey = DEFAULT_PUBLIC_API_KEY;
        private HttpClient httpClient;
        private Duration requestTimeout = Duration.ofSeconds(10);

        public Builder baseUrl(URI v) { this.baseUrl = v; return this; }
        public Builder apiKey(String v) { this.apiKey = v; return this; }
        public Builder httpClient(HttpClient v) { this.httpClient = v; return this; }
        public Builder requestTimeout(Duration v) { this.requestTimeout = v; return this; }

        public FunxyzConfig build() {
            HttpClient hc = httpClient != null ? httpClient : HttpClient.newHttpClient();
            return new FunxyzConfig(baseUrl, apiKey, hc, requestTimeout);
        }
    }
}
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `cd /Users/bmtaka/Downloads/java-polymarket-clob && mvn -o test -Dtest=FunxyzConfigTest -q`
Expected: `BUILD SUCCESS`，3 用例全过。

- [ ] **Step 5: 提交**

```bash
cd /Users/bmtaka/Downloads/java-polymarket-clob
git add src/main/java/com/polymarket/clob/funxyz/FunxyzConfig.java \
        src/test/java/com/polymarket/clob/funxyz/FunxyzConfigTest.java
git commit -m "feat(funxyz): FunxyzConfig record + Builder

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: `FunxyzClient` happy path（含请求形状/请求头校验）

**Files:**
- Create: `src/main/java/com/polymarket/clob/funxyz/FunxyzClient.java`
- Test: `src/test/java/com/polymarket/clob/funxyz/FunxyzClientTest.java`

> 一次性写出 happy path + 请求体形状 + 请求头校验三个用例，因为它们覆盖同一段最小实现。

- [ ] **Step 1: 写失败的测试**

```java
// src/test/java/com/polymarket/clob/funxyz/FunxyzClientTest.java
package com.polymarket.clob.funxyz;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.CompletionException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FunxyzClientTest {

    /** HAR 抓的固定 EOA。 */
    private static final Address EOA = Address.fromHex("0x5f0fE47194FAC5FdE131C58359b614F520Db1342");
    /** HAR 抓的 recipient（Deposit Wallet）。 */
    private static final Address RECIPIENT = Address.fromHex("0xB51b3627E851EdeaFD81792F012C066805b6dFdE");

    private static final String OK_BODY = """
            {"depositAddr":"0x4C741213d8519429002ab3E69DE9620fb9b48C69",
             "solanaAddr":"CobugN8o4CnNVYL9jj7NGRDrdG2hJxAwMiPNZug7N6Pk",
             "tronAddr":"TN4Vfn2wjVZGM8z8oy8MFLSwcW36bs3418",
             "btcAddrSegwit":"bc1q7hum6lx3rad7xzsfjxrle4ryk6ev527pk0xzws",
             "blocked":false}
            """;

    private WireMockServer server;
    private FunxyzClient client;

    @BeforeEach
    void start() {
        server = new WireMockServer(0);
        server.start();
        client = new FunxyzClient(FunxyzConfig.builder()
                .baseUrl(URI.create(server.baseUrl()))
                .apiKey("test-key-123")
                .build());
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void happyPathReturnsAllFourAddresses() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/eoa")).willReturn(okJson(OK_BODY)));

        DepositAddresses addrs = client.getDepositAddresses(EOA, RECIPIENT).get();

        assertThat(addrs.evm())
                .isEqualTo(Address.fromHex("0x4C741213d8519429002ab3E69DE9620fb9b48C69"));
        assertThat(addrs.solana()).isEqualTo("CobugN8o4CnNVYL9jj7NGRDrdG2hJxAwMiPNZug7N6Pk");
        assertThat(addrs.tron()).isEqualTo("TN4Vfn2wjVZGM8z8oy8MFLSwcW36bs3418");
        assertThat(addrs.btcSegwit()).isEqualTo("bc1q7hum6lx3rad7xzsfjxrle4ryk6ev527pk0xzws");
    }

    @Test
    void requestBodyHasRequiredFields() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/eoa")).willReturn(okJson(OK_BODY)));

        client.getDepositAddresses(EOA, RECIPIENT).get();

        // 必填字段全到位
        server.verify(postRequestedFor(urlEqualTo("/v1/eoa"))
                .withRequestBody(matchingJsonPath("$.userId",
                        equalTo("0x5f0fe47194fac5fde131c58359b614f520db1342")))
                .withRequestBody(matchingJsonPath("$.recipientAddr",
                        equalTo("0xb51b3627e851edeafd81792f012c066805b6dfde")))
                .withRequestBody(matchingJsonPath("$.toChainId", equalTo("137")))
                .withRequestBody(matchingJsonPath("$.toTokenAddress",
                        equalTo("0x2791bca1f2de4661ed88a30c99a7a9449aa84174")))
                .withRequestBody(matchingJsonPath("$.clientMetadata.id"))
                .withRequestBody(matchingJsonPath(
                        "$.clientMetadata.selectedPaymentMethodInfo.paymentMethod",
                        equalTo("token_transfer"))));
    }

    @Test
    void requestHeadersSetCorrectly() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/eoa")).willReturn(okJson(OK_BODY)));

        client.getDepositAddresses(EOA, RECIPIENT).get();

        server.verify(postRequestedFor(urlEqualTo("/v1/eoa"))
                .withHeader("content-type", containing("application/json"))
                .withHeader("origin", equalTo("https://polymarket.com"))
                .withHeader("referer", equalTo("https://polymarket.com/"))
                .withHeader("x-api-key", equalTo("test-key-123")));
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `cd /Users/bmtaka/Downloads/java-polymarket-clob && mvn -o test -Dtest=FunxyzClientTest -q`
Expected: 编译失败 `cannot find symbol: class FunxyzClient`。

- [ ] **Step 3: 实现 `FunxyzClient`**

```java
// src/main/java/com/polymarket/clob/funxyz/FunxyzClient.java
package com.polymarket.clob.funxyz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.polymarket.clob.http.JsonCodec;
import com.polymarket.clob.model.Address;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * fun.xyz 法币入金地址客户端。封装单接口 {@code POST /v1/eoa}：给定 EOA + recipient，
 * 返回该 EOA 在 fun.xyz 上固定映射的四链入金中转地址。
 *
 * <p>线程安全：本类无可变状态，{@link java.net.http.HttpClient} 自身线程安全。同一实例可并发调用。</p>
 */
public final class FunxyzClient {

    private static final Logger log = LoggerFactory.getLogger(FunxyzClient.class);

    private static final String ORIGIN  = "https://polymarket.com";
    private static final String REFERER = "https://polymarket.com/";
    private static final String TO_CHAIN_ID    = "137";
    private static final String USDC_E_POLYGON = "0x2791bca1f2de4661ed88a30c99a7a9449aa84174";

    /**
     * 占位 clientMetadata。fun.xyz 不校验内容但缺整字段会 400。
     * 来自 2026-05-07 polymarket.com 前端抓包，最小化保留结构骨架。
     */
    private static final String CLIENT_METADATA_STUB = """
            {"id":"","startTimestampMs":0,"finalDollarValue":0,"latestQuote":null,"depositAddress":null,
             "initSettings":{"config":{"targetAsset":"0x","targetChain":"","targetAssetTicker":"","checkoutItemTitle":""}},
             "selectedSourceAssetInfo":{"address":"0x","symbol":"","chainId":"","iconSrc":null},
             "selectedPaymentMethodInfo":{"paymentMethod":"token_transfer","title":"QR Code Transfer","description":""}}
            """;

    private final FunxyzConfig cfg;
    private final ObjectMapper mapper;

    public FunxyzClient(FunxyzConfig cfg) {
        this.cfg = Objects.requireNonNull(cfg, "cfg");
        this.mapper = JsonCodec.objectMapper();
    }

    /**
     * 取 fun.xyz 给该 EOA 的固定四链入金地址。
     *
     * @param eoa       用户 EOA（fun.xyz 按此持久映射）
     * @param recipient 链上 USDC 最终转发目标（一般是 Polymarket Deposit Wallet）
     * @return 固定映射的四链地址
     * @throws IllegalArgumentException 任一参数为 {@code null}（同步抛出，不进 future）
     */
    public CompletableFuture<DepositAddresses> getDepositAddresses(Address eoa, Address recipient) {
        if (eoa == null) throw new IllegalArgumentException("eoa must not be null");
        if (recipient == null) throw new IllegalArgumentException("recipient must not be null");

        String body = buildRequestBody(eoa, recipient);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(cfg.baseUrl().resolve("/v1/eoa"))
                .header("content-type", "application/json")
                .header("origin", ORIGIN)
                .header("referer", REFERER)
                .header("x-api-key", cfg.apiKey())
                .timeout(cfg.requestTimeout())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        log.info("funxyz POST /v1/eoa eoa={} recipient={}", eoa.toLowerHex(), recipient.toLowerHex());

        return cfg.httpClient()
                .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .handle((resp, err) -> {
                    if (err != null) {
                        Throwable cause = err instanceof CompletionException ce && ce.getCause() != null
                                ? ce.getCause() : err;
                        if (cause instanceof HttpTimeoutException) {
                            throw new FunxyzException(
                                    "funxyz request timed out after "
                                            + cfg.requestTimeout().toSeconds() + "s",
                                    -1, cause);
                        }
                        throw new FunxyzException(
                                "funxyz request failed: " + cause.getMessage(), -1, cause);
                    }
                    return parseResponse(resp);
                });
    }

    private String buildRequestBody(Address eoa, Address recipient) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("userId", eoa.toLowerHex());
            root.put("recipientAddr", recipient.toLowerHex());
            root.put("toChainId", TO_CHAIN_ID);
            root.put("toTokenAddress", USDC_E_POLYGON);
            root.set("clientMetadata", mapper.readTree(CLIENT_METADATA_STUB));
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new FunxyzException("failed to build request body", -1, e);
        }
    }

    private DepositAddresses parseResponse(HttpResponse<String> resp) {
        if (resp.statusCode() != 200) {
            String snippet = truncate(resp.body(), 500);
            log.warn("funxyz POST /v1/eoa returned {}: {}", resp.statusCode(), snippet);
            throw new FunxyzException(
                    "funxyz POST /v1/eoa returned " + resp.statusCode() + ": " + snippet,
                    resp.statusCode());
        }

        JsonNode root;
        try {
            root = mapper.readTree(resp.body());
        } catch (Exception e) {
            throw new FunxyzException(
                    "funxyz returned malformed JSON: " + truncate(resp.body(), 200),
                    200, e);
        }

        if (root.path("blocked").asBoolean(false)) {
            throw new FunxyzException(FunxyzException.BLOCKED_MESSAGE, 200);
        }

        String depositAddr = root.path("depositAddr").asText("");
        // 必须是 "0x" + 40 hex 字符 = 42 字符
        if (!depositAddr.startsWith("0x") || depositAddr.length() != 42) {
            throw new FunxyzException("funxyz response missing or malformed depositAddr", 200);
        }

        return new DepositAddresses(
                Address.fromHex(depositAddr),
                root.path("solanaAddr").asText(""),
                root.path("tronAddr").asText(""),
                root.path("btcAddrSegwit").asText("")
        );
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `cd /Users/bmtaka/Downloads/java-polymarket-clob && mvn -o test -Dtest=FunxyzClientTest -q`
Expected: `BUILD SUCCESS`，3 用例全过。

- [ ] **Step 5: 提交**

```bash
cd /Users/bmtaka/Downloads/java-polymarket-clob
git add src/main/java/com/polymarket/clob/funxyz/FunxyzClient.java \
        src/test/java/com/polymarket/clob/funxyz/FunxyzClientTest.java
git commit -m "feat(funxyz): FunxyzClient happy path (POST /v1/eoa)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: `blocked: true` 走异常

**Files:**
- Modify: `src/test/java/com/polymarket/clob/funxyz/FunxyzClientTest.java`

> Task 4 实现里已经写了 `blocked` 检查（YAGNI 原则下我本可以延后，但因 happy path 实现自然就该带上 —— 这条不算例外）。本任务用一个测试 lock down 行为。

- [ ] **Step 1: 写失败的测试（追加到 `FunxyzClientTest`）**

在 `FunxyzClientTest` 类末尾追加：

```java
    @Test
    void blockedTrueThrowsBlockedException() {
        String blockedBody = """
                {"depositAddr":"0x4C741213d8519429002ab3E69DE9620fb9b48C69",
                 "solanaAddr":"","tronAddr":"","btcAddrSegwit":"",
                 "blocked":true}
                """;
        server.stubFor(post(urlEqualTo("/v1/eoa")).willReturn(okJson(blockedBody)));

        assertThatThrownBy(() -> client.getDepositAddresses(EOA, RECIPIENT).get())
                .isInstanceOf(java.util.concurrent.ExecutionException.class)
                .hasCauseInstanceOf(FunxyzException.class)
                .satisfies(t -> {
                    FunxyzException fe = (FunxyzException) t.getCause();
                    assertThat(fe.isBlocked()).isTrue();
                    assertThat(fe.httpStatus()).isEqualTo(200);
                });
    }
```

- [ ] **Step 2: 跑测试，确认通过（实现已存在）**

Run: `cd /Users/bmtaka/Downloads/java-polymarket-clob && mvn -o test -Dtest=FunxyzClientTest#blockedTrueThrowsBlockedException -q`
Expected: PASS。

- [ ] **Step 3: 提交**

```bash
cd /Users/bmtaka/Downloads/java-polymarket-clob
git add src/test/java/com/polymarket/clob/funxyz/FunxyzClientTest.java
git commit -m "test(funxyz): blocked: true 抛 FunxyzException

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: 非 200 状态码携带 status 抛错

**Files:**
- Modify: `src/test/java/com/polymarket/clob/funxyz/FunxyzClientTest.java`

- [ ] **Step 1: 追加测试**

```java
    @Test
    void http401ThrowsWithStatusCode() {
        server.stubFor(post(urlEqualTo("/v1/eoa"))
                .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"invalid api key\"}")));

        assertThatThrownBy(() -> client.getDepositAddresses(EOA, RECIPIENT).get())
                .hasCauseInstanceOf(FunxyzException.class)
                .satisfies(t -> {
                    FunxyzException fe = (FunxyzException) t.getCause();
                    assertThat(fe.httpStatus()).isEqualTo(401);
                    assertThat(fe.isTransport()).isFalse();
                    assertThat(fe.isBlocked()).isFalse();
                    assertThat(fe.getMessage()).contains("401").contains("invalid api key");
                });
    }

    @Test
    void http500ThrowsWithStatusCode() {
        server.stubFor(post(urlEqualTo("/v1/eoa"))
                .willReturn(aResponse().withStatus(500).withBody("internal server error")));

        assertThatThrownBy(() -> client.getDepositAddresses(EOA, RECIPIENT).get())
                .hasCauseInstanceOf(FunxyzException.class)
                .satisfies(t -> assertThat(((FunxyzException) t.getCause()).httpStatus())
                        .isEqualTo(500));
    }
```

- [ ] **Step 2: 跑测试，确认通过**

Run: `cd /Users/bmtaka/Downloads/java-polymarket-clob && mvn -o test -Dtest=FunxyzClientTest -q`
Expected: 全 5 用例 PASS。

- [ ] **Step 3: 提交**

```bash
cd /Users/bmtaka/Downloads/java-polymarket-clob
git add src/test/java/com/polymarket/clob/funxyz/FunxyzClientTest.java
git commit -m "test(funxyz): 4xx/5xx 抛 FunxyzException 携带 status

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: 200 + 损坏 JSON 抛错

**Files:**
- Modify: `src/test/java/com/polymarket/clob/funxyz/FunxyzClientTest.java`

- [ ] **Step 1: 追加测试**

```java
    @Test
    void malformedJsonThrowsWithCause() {
        server.stubFor(post(urlEqualTo("/v1/eoa"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{not valid json")));

        assertThatThrownBy(() -> client.getDepositAddresses(EOA, RECIPIENT).get())
                .hasCauseInstanceOf(FunxyzException.class)
                .satisfies(t -> {
                    FunxyzException fe = (FunxyzException) t.getCause();
                    assertThat(fe.httpStatus()).isEqualTo(200);
                    assertThat(fe.getCause()).isNotNull();  // Jackson 异常
                    assertThat(fe.getMessage()).contains("malformed JSON");
                });
    }
```

- [ ] **Step 2: 跑测试，确认通过**

Run: `cd /Users/bmtaka/Downloads/java-polymarket-clob && mvn -o test -Dtest=FunxyzClientTest#malformedJsonThrowsWithCause -q`
Expected: PASS。

- [ ] **Step 3: 提交**

```bash
cd /Users/bmtaka/Downloads/java-polymarket-clob
git add src/test/java/com/polymarket/clob/funxyz/FunxyzClientTest.java
git commit -m "test(funxyz): 损坏 JSON 抛 FunxyzException

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: `depositAddr` 缺失/非合法地址抛错

**Files:**
- Modify: `src/test/java/com/polymarket/clob/funxyz/FunxyzClientTest.java`

- [ ] **Step 1: 追加测试**

```java
    @Test
    void missingDepositAddrThrows() {
        server.stubFor(post(urlEqualTo("/v1/eoa"))
                .willReturn(okJson("{\"solanaAddr\":\"x\",\"blocked\":false}")));

        assertThatThrownBy(() -> client.getDepositAddresses(EOA, RECIPIENT).get())
                .hasCauseInstanceOf(FunxyzException.class)
                .satisfies(t -> {
                    FunxyzException fe = (FunxyzException) t.getCause();
                    assertThat(fe.httpStatus()).isEqualTo(200);
                    assertThat(fe.getMessage()).contains("missing or malformed depositAddr");
                });
    }

    @Test
    void malformedDepositAddrThrows() {
        // 非 0x 前缀 + 长度错
        server.stubFor(post(urlEqualTo("/v1/eoa"))
                .willReturn(okJson("{\"depositAddr\":\"not-an-address\",\"blocked\":false}")));

        assertThatThrownBy(() -> client.getDepositAddresses(EOA, RECIPIENT).get())
                .hasCauseInstanceOf(FunxyzException.class)
                .satisfies(t -> assertThat(((FunxyzException) t.getCause()).getMessage())
                        .contains("missing or malformed depositAddr"));
    }
```

- [ ] **Step 2: 跑测试，确认通过**

Run: `cd /Users/bmtaka/Downloads/java-polymarket-clob && mvn -o test -Dtest=FunxyzClientTest -q`
Expected: 全部 PASS。

- [ ] **Step 3: 提交**

```bash
cd /Users/bmtaka/Downloads/java-polymarket-clob
git add src/test/java/com/polymarket/clob/funxyz/FunxyzClientTest.java
git commit -m "test(funxyz): depositAddr 缺失/非合法抛 FunxyzException

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: null 参数同步抛 `IllegalArgumentException`

**Files:**
- Modify: `src/test/java/com/polymarket/clob/funxyz/FunxyzClientTest.java`

- [ ] **Step 1: 追加测试**

```java
    @Test
    void nullEoaThrowsSynchronously() {
        // 注意：是同步 throw，不是 future 完成时抛
        assertThatThrownBy(() -> client.getDepositAddresses(null, RECIPIENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eoa");
    }

    @Test
    void nullRecipientThrowsSynchronously() {
        assertThatThrownBy(() -> client.getDepositAddresses(EOA, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recipient");
    }
```

- [ ] **Step 2: 跑测试，确认通过**

Run: `cd /Users/bmtaka/Downloads/java-polymarket-clob && mvn -o test -Dtest=FunxyzClientTest -q`
Expected: 全部 PASS。

- [ ] **Step 3: 提交**

```bash
cd /Users/bmtaka/Downloads/java-polymarket-clob
git add src/test/java/com/polymarket/clob/funxyz/FunxyzClientTest.java
git commit -m "test(funxyz): null 参数同步抛 IllegalArgumentException

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: 传输错（连接拒绝）走 `isTransport()`

**Files:**
- Modify: `src/test/java/com/polymarket/clob/funxyz/FunxyzClientTest.java`

> 通过把 baseUrl 指向一个**已停止**的端口模拟连接拒绝。

- [ ] **Step 1: 追加测试**

```java
    @Test
    void transportErrorWrapsAsFunxyzExceptionWithStatusMinusOne() throws Exception {
        // 先抓一个被使用过的端口号,然后停止 server,使下次连接被拒
        int port = server.port();
        server.stop();

        FunxyzClient brokenClient = new FunxyzClient(FunxyzConfig.builder()
                .baseUrl(URI.create("http://localhost:" + port))
                .apiKey("k")
                .build());

        assertThatThrownBy(() -> brokenClient.getDepositAddresses(EOA, RECIPIENT).get())
                .hasCauseInstanceOf(FunxyzException.class)
                .satisfies(t -> {
                    FunxyzException fe = (FunxyzException) t.getCause();
                    assertThat(fe.isTransport()).isTrue();
                    assertThat(fe.httpStatus()).isEqualTo(-1);
                    assertThat(fe.getCause()).isNotNull();
                });

        // 重启,避免 @AfterEach 抛错
        server = new WireMockServer(0);
        server.start();
    }
```

- [ ] **Step 2: 跑测试，确认通过**

Run: `cd /Users/bmtaka/Downloads/java-polymarket-clob && mvn -o test -Dtest=FunxyzClientTest -q`
Expected: 全部 PASS（共 11 用例）。

- [ ] **Step 3: 提交**

```bash
cd /Users/bmtaka/Downloads/java-polymarket-clob
git add src/test/java/com/polymarket/clob/funxyz/FunxyzClientTest.java
git commit -m "test(funxyz): 连接拒绝走 isTransport() + httpStatus=-1

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: `package-info.java`

**Files:**
- Create: `src/main/java/com/polymarket/clob/funxyz/package-info.java`

- [ ] **Step 1: 写文件**

```java
// src/main/java/com/polymarket/clob/funxyz/package-info.java
/**
 * fun.xyz 法币入金提供商客户端。
 *
 * <h2>定位</h2>
 * Polymarket 网页端用 fun.xyz 把法币（信用卡 / Apple Pay / 银行转账，via Swapped/MoonPay）
 * 转成 Polygon USDC.e 打到用户的 Deposit Wallet。{@link com.polymarket.clob.funxyz.FunxyzClient}
 * 封装其中的 {@code POST /v1/eoa} 接口：给定 EOA + recipient，返回 fun.xyz 服务端为该 EOA 持久分配的
 * 多链入金中转地址。
 *
 * <h2>与 onboard/ deposit/ 的关系</h2>
 * 完全独立。本包不导入 {@code chain.*} / {@code deposit.*} / {@code onboard.*}，
 * 也不被它们导入。fun.xyz 是第三方服务，与链上 Deposit Wallet 流并列；调用方按需组合。
 *
 * <h2>线程安全</h2>
 * {@link com.polymarket.clob.funxyz.FunxyzClient} 实例不可变、线程安全。
 *
 * <h2>错误模型</h2>
 * 所有失败收敛到 {@link com.polymarket.clob.funxyz.FunxyzException}：
 * {@code httpStatus = -1} 表传输错，{@code isBlocked()} 表 fun.xyz 风控拒绝。
 *
 * @see com.polymarket.clob.funxyz.FunxyzClient
 */
package com.polymarket.clob.funxyz;
```

- [ ] **Step 2: 编译**

Run: `cd /Users/bmtaka/Downloads/java-polymarket-clob && mvn -o compile -q`
Expected: `BUILD SUCCESS`。

- [ ] **Step 3: 提交**

```bash
cd /Users/bmtaka/Downloads/java-polymarket-clob
git add src/main/java/com/polymarket/clob/funxyz/package-info.java
git commit -m "docs(funxyz): package-info javadoc

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 12: 集成测试（env-gated，默认跳过）

**Files:**
- Create: `src/test/java/com/polymarket/clob/funxyz/FunxyzClientIntegrationTest.java`

- [ ] **Step 1: 写文件**

```java
// src/test/java/com/polymarket/clob/funxyz/FunxyzClientIntegrationTest.java
package com.polymarket.clob.funxyz;

import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 打真实 https://api.fun.xyz/v1/eoa。
 * <p>默认跳过；本机运行：{@code FUNXYZ_IT=true mvn test -Dtest=FunxyzClientIntegrationTest}</p>
 */
@EnabledIfEnvironmentVariable(named = "FUNXYZ_IT", matches = "true")
class FunxyzClientIntegrationTest {

    /** HAR 抓到的固定 EOA。 */
    private static final Address EOA =
            Address.fromHex("0x5f0fE47194FAC5FdE131C58359b614F520Db1342");
    /** HAR 抓到的 recipient。 */
    private static final Address RECIPIENT =
            Address.fromHex("0xB51b3627E851EdeaFD81792F012C066805b6dFdE");
    /** fun.xyz 应该持久映射到这个 EVM 地址。 */
    private static final Address EXPECTED_EVM =
            Address.fromHex("0x4C741213d8519429002ab3E69DE9620fb9b48C69");

    @Test
    void liveCallReturnsHarAddress() throws Exception {
        FunxyzClient client = new FunxyzClient(FunxyzConfig.builder().build());

        DepositAddresses addrs = client.getDepositAddresses(EOA, RECIPIENT).get();

        assertThat(addrs.evm()).isEqualTo(EXPECTED_EVM);
        assertThat(addrs.solana()).isNotEmpty();
        assertThat(addrs.tron()).isNotEmpty();
        assertThat(addrs.btcSegwit()).isNotEmpty();
    }
}
```

- [ ] **Step 2: 验证默认跳过**

Run: `cd /Users/bmtaka/Downloads/java-polymarket-clob && mvn -o test -Dtest=FunxyzClientIntegrationTest -q`
Expected: `BUILD SUCCESS`，但 surefire 报告显示 1 个测试 skipped（因为 `FUNXYZ_IT` 未设）。

- [ ] **Step 3: 验证开启时打外网（联网手动跑）**

Run: `cd /Users/bmtaka/Downloads/java-polymarket-clob && FUNXYZ_IT=true mvn test -Dtest=FunxyzClientIntegrationTest -q`
Expected: PASS（实测 fun.xyz 给该 EOA 返回 `0x4C741213...`）。**如果失败可能原因**：
- fun.xyz 改了 API key（401）→ 用 `FunxyzConfig.builder().apiKey("...").build()` 临时换；并通知 spec 更新
- fun.xyz 改了 schema → 解析层应已通过缺字段 / 非法地址抛错；查日志的 status 与 body
- 没网 / 防火墙 → `isTransport() == true`

- [ ] **Step 4: 提交**

```bash
cd /Users/bmtaka/Downloads/java-polymarket-clob
git add src/test/java/com/polymarket/clob/funxyz/FunxyzClientIntegrationTest.java
git commit -m "test(funxyz): env-gated 集成测试 (FUNXYZ_IT=true)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 13: `FunxyzAddressLookupExample`

**Files:**
- Create: `src/main/java/com/polymarket/clob/example/FunxyzAddressLookupExample.java`

> example 跨包调用 funxyz/ 与 deposit/，仅在 example 层耦合。需要环境变量 `PK` 与 `RPC_URL`。

- [ ] **Step 1: 看现有 example 风格**

Run: `head -40 /Users/bmtaka/Downloads/java-polymarket-clob/src/main/java/com/polymarket/clob/example/OnboarderExample.java`
（手动查看，确保你的 example 命名 / 包导入 / `main` 签名风格一致；不强制改本计划，但保持一致）

- [ ] **Step 2: 写文件**

```java
// src/main/java/com/polymarket/clob/example/FunxyzAddressLookupExample.java
package com.polymarket.clob.example;

import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.chain.Web3jEvmRpcClient;
import com.polymarket.clob.deposit.DepositWalletDerivation;
import com.polymarket.clob.chain.DepositWalletReads;
import com.polymarket.clob.funxyz.DepositAddresses;
import com.polymarket.clob.funxyz.FunxyzClient;
import com.polymarket.clob.funxyz.FunxyzConfig;
import com.polymarket.clob.model.Address;

import java.net.URI;

/**
 * 端到端 demo：读 PK → 派生 Polymarket Deposit Wallet → 取 fun.xyz 四链入金地址 → 打印。
 *
 * <p>环境变量：
 * <ul>
 *   <li>{@code PK} —— EOA 私钥 hex（必填）</li>
 *   <li>{@code RPC_URL} —— Polygon JSON-RPC 端点（必填，用于派生 Deposit Wallet）</li>
 *   <li>{@code FUNXYZ_API_KEY} —— 自定义 fun.xyz key（可选；不设走默认 public key）</li>
 * </ul>
 *
 * <p>运行：{@code mvn compile exec:java -Dexec.mainClass=com.polymarket.clob.example.FunxyzAddressLookupExample}
 */
public final class FunxyzAddressLookupExample {

    private FunxyzAddressLookupExample() {}

    public static void main(String[] args) throws Exception {
        String pk = requireEnv("PK");
        String rpcUrl = requireEnv("RPC_URL");
        String funxyzKey = System.getenv("FUNXYZ_API_KEY");  // 可选

        Signer signer = LocalSigner.fromPrivateKeyHex(pk);
        Address eoa = signer.address();

        // 1. 派生 Polymarket Deposit Wallet（链上读)
        var rpc = new Web3jEvmRpcClient(URI.create(rpcUrl));
        var reads = new DepositWalletReads(rpc, 137L);
        Address wallet = new DepositWalletDerivation(reads).predictWalletAddress(eoa).get();

        // 2. 取 fun.xyz 入金地址
        FunxyzConfig.Builder cfgBuilder = FunxyzConfig.builder();
        if (funxyzKey != null && !funxyzKey.isBlank()) cfgBuilder.apiKey(funxyzKey);
        FunxyzClient client = new FunxyzClient(cfgBuilder.build());

        DepositAddresses addrs = client.getDepositAddresses(eoa, wallet).get();

        // 3. 打印
        System.out.println("EOA:             " + eoa);
        System.out.println("Deposit Wallet:  " + wallet);
        System.out.println();
        System.out.println("fun.xyz 入金地址（同一 EOA 永远固定）:");
        System.out.println("  EVM (Polygon):  " + addrs.evm());
        System.out.println("  Solana:         " + addrs.solana());
        System.out.println("  Tron:           " + addrs.tron());
        System.out.println("  BTC (segwit):   " + addrs.btcSegwit());
    }

    private static String requireEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("env var " + name + " not set");
        }
        return v;
    }
}
```

- [ ] **Step 3: 编译**

Run: `cd /Users/bmtaka/Downloads/java-polymarket-clob && mvn -o compile -q`
Expected: `BUILD SUCCESS`。

- [ ] **Step 4: 提交**

```bash
cd /Users/bmtaka/Downloads/java-polymarket-clob
git add src/main/java/com/polymarket/clob/example/FunxyzAddressLookupExample.java
git commit -m "feat(example): FunxyzAddressLookupExample

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 14: README 增量

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 找插入点**

Run: `grep -n "## " /Users/bmtaka/Downloads/java-polymarket-clob/README.md | head -20`
插入位置：在最后一个二级章节（"使用示例"或"使用"那类）的末尾追加新的三级标题。具体行号现场看。

- [ ] **Step 2: 在 README 末尾追加**

```markdown

### Fun.xyz 法币入金地址（v2）

`com.polymarket.clob.funxyz` 包封装了 fun.xyz 的 `POST /v1/eoa`。给定 EOA + recipient（一般是 Polymarket Deposit Wallet），返回 fun.xyz 服务端为该 EOA 持久分配的四链入金中转地址（EVM/Solana/Tron/BTC）。Polymarket 前端用这条接口给"用法币买 USDC.e"准备入金地址。

```java
import com.polymarket.clob.funxyz.*;
import com.polymarket.clob.model.Address;

FunxyzClient client = new FunxyzClient(FunxyzConfig.builder().build());

Address eoa       = Address.fromHex("0x5f0fE47194FAC5FdE131C58359b614F520Db1342");
Address recipient = Address.fromHex("0xB51b3627E851EdeaFD81792F012C066805b6dFdE");

DepositAddresses addrs = client.getDepositAddresses(eoa, recipient).join();
System.out.println(addrs.evm());        // 0x4C741213d8519429002ab3E69DE9620fb9b48C69
System.out.println(addrs.solana());     // CobugN8o4CnNVYL9jj7NGRDrdG2hJxAwMiPNZug7N6Pk
```

`apiKey` 默认是 Polymarket 前端公开 key，可用 `FunxyzConfig.builder().apiKey(...).build()` 覆盖。`recipient` 由调用方决定（如用 `DepositWalletDerivation.predictWalletAddress(eoa)` 派生 Polymarket Deposit Wallet）。

参考 `example/FunxyzAddressLookupExample.java` 跑一个端到端 demo。
```

- [ ] **Step 3: 提交**

```bash
cd /Users/bmtaka/Downloads/java-polymarket-clob
git add README.md
git commit -m "docs(readme): 添加 Fun.xyz 法币入金地址 (v2) 章节

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 15: CHANGELOG

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1: 看现有结构**

Run: `head -25 /Users/bmtaka/Downloads/java-polymarket-clob/CHANGELOG.md`
找到 `## [Unreleased]` 节（如果没有就在文件顶部 `# Changelog` 下新建），把新增项写进 Added 子节。

- [ ] **Step 2: 编辑文件**

在 `## [Unreleased]` 下的 `### Added` 子节追加：

```markdown
- `funxyz/` 包：fun.xyz 法币入金地址客户端
  - `FunxyzClient.getDepositAddresses(eoa, recipient)` 封装 `POST api.fun.xyz/v1/eoa`
  - `DepositAddresses` 返回四链固定映射地址（EVM/Solana/Tron/BTC）
  - `FunxyzConfig` 默认带 Polymarket 前端公开 key，可覆盖
  - `FunxyzAddressLookupExample` 端到端 demo
```

如果 `## [Unreleased]` 不存在，先在 `# Changelog` 下方新建：

```markdown
## [Unreleased]

### Added

- `funxyz/` 包：fun.xyz 法币入金地址客户端
  - ...（同上）
```

- [ ] **Step 3: 提交**

```bash
cd /Users/bmtaka/Downloads/java-polymarket-clob
git add CHANGELOG.md
git commit -m "docs(changelog): unreleased — funxyz/ 包

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 16: 全量回归 + 静态检查

**Files:** 无新建/修改

- [ ] **Step 1: 跑全量测试**

Run: `cd /Users/bmtaka/Downloads/java-polymarket-clob && mvn -o test -q`
Expected: `BUILD SUCCESS`。所有原有测试 + 新增 `funxyz/` 测试全过。`FunxyzClientIntegrationTest` 应被 skip（环境变量未设）。

- [ ] **Step 2: 跑联网集成测试（手动确认）**

Run: `cd /Users/bmtaka/Downloads/java-polymarket-clob && FUNXYZ_IT=true mvn test -Dtest=FunxyzClientIntegrationTest -q`
Expected: PASS（断言 fun.xyz 还在按 HAR 时的映射返回 `0x4C741213...`）。如果失败按 Task 12 Step 3 的清单排查。

- [ ] **Step 3: 检查包边界（funxyz 不依赖 chain/deposit/onboard）**

Run:
```bash
cd /Users/bmtaka/Downloads/java-polymarket-clob && \
  grep -RE "^import com.polymarket.clob.(chain|deposit|onboard|gamma|order|api|http|trade)" \
       src/main/java/com/polymarket/clob/funxyz/
```
Expected: 仅出现 `import com.polymarket.clob.http.JsonCodec;` 和 `import com.polymarket.clob.model.Address;` —— 这两个是项目核心基础设施，被允许。**不应**出现 `chain/ deposit/ onboard/ gamma/ order/ api/ trade/` 包的导入。

- [ ] **Step 4: 不需要提交**（这一任务只是验证）

---

## Self-Review

- ✅ Spec coverage：spec §1.2 范围 → Task 1-13；spec §3 包结构 → Task 1-3,11；spec §4 数据流 → Task 4；spec §5 错误模型 → Task 5-10；spec §6 测试 → Task 4-10,12；spec §7 README/Example → Task 13-14；spec §8 风险 → Task 12 Step 3 排查清单
- ✅ Placeholder 扫描：每步均有具体代码 / 命令 / 期望输出，无 TBD
- ✅ Type 一致性：`Address.fromHex(...)` 在所有任务中保持；`FunxyzException(message, httpStatus)` / `(message, httpStatus, cause)` 两种 ctor 在 Task 1 定义、Task 4-10 测试中使用一致；`DepositAddresses(evm, solana, tron, btcSegwit)` 字段名跨 Task 2/4/12/13 一致
- ✅ 范围聚焦：单独 spec → 单独 plan，无独立子系统需要拆
