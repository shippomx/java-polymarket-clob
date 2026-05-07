# FunxyzClient apiKey 启动时从 sdk-cdn 解析 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `FunxyzClient` 的 `x-api-key` header 不再依赖编译期硬编码常量；构造时同步从 `https://sdk-cdn.fun.xyz/flags/v0/config.json` 解析 `flags.token_transfer_source_chains_and_assets.overrides[0].if_any[0].values[0]`，CDN 不可达时回退到 `DEFAULT_PUBLIC_API_KEY`，调用方仍可用 `builder.apiKey(...)` 显式覆盖跳过 CDN。

**Architecture:** `FunxyzConfig` record 加两个字段 `flagsConfigUrl` / `flagsRequestTimeout`，Builder 默认 apiKey 改 `null`（语义：未显式 = 从 CDN 解析）。`FunxyzClient` 构造函数末尾同步执行私有方法 `resolveApiKey(cfg)`，把结果存到 `final String resolvedApiKey` 字段，后续每次 `/v1/eoa` 请求 header 用此值。CDN 路径用 Jackson `path()` 链式取值（不抛 NPE），`catch (Exception e)` 兜底回退 `DEFAULT_PUBLIC_API_KEY`，构造永不抛异常。

**Tech Stack:** Java 17、`java.net.http.HttpClient`（同步 `send()`）、Jackson 2.18.0、JUnit Jupiter 5.11.3、AssertJ、WireMock 3.9.2（test scope，已在 pom），SLF4J。

**Spec:** `docs/superpowers/specs/2026-05-08-funxyz-apikey-from-cdn-design.md`（commit `a4457cd`）

---

## File Structure

| 文件 | 操作 | 职责 |
|---|---|---|
| `src/main/java/com/polymarket/clob/funxyz/FunxyzConfig.java` | 修改 | record 加 2 字段（`flagsConfigUrl`、`flagsRequestTimeout`）+ Builder 默认 apiKey 改 `null` + 暴露 `DEFAULT_FLAGS_CONFIG_URL` 常量 |
| `src/main/java/com/polymarket/clob/funxyz/FunxyzClient.java` | 修改 | 加 `private final String resolvedApiKey` 字段；构造函数末尾调 `resolveApiKey(cfg)`；`/v1/eoa` request 的 `x-api-key` header 改用此字段；新增私有方法 `resolveApiKey(FunxyzConfig)` |
| `src/test/java/com/polymarket/clob/funxyz/FunxyzConfigTest.java` | 修改 | 更新 `buildExposesAllDefaults`、`overridesAreHonored`、`publicConstantsExposed` 三个测试，覆盖新字段和默认值变化 |
| `src/test/java/com/polymarket/clob/funxyz/FunxyzClientTest.java` | 不动 | 现有用例 `@BeforeEach` 都传了 `.apiKey("test-key-123")`，自动走"显式跳过 CDN"分支，无需改动 |
| `src/test/java/com/polymarket/clob/funxyz/FunxyzClientCdnResolveTest.java` | 创建 | 新测试类专门验证 CDN apiKey 解析行为：CDN happy path、跳过 CDN、各种失败回退到 DEFAULT |

不需要改 `FunxyzException.java`、`DepositAddresses.java`、`package-info.java`、`FunxyzClientIntegrationTest.java`。

---

## Task 1: 升级 `FunxyzConfig` 与 `FunxyzConfigTest`

**Files:**
- Modify: `src/test/java/com/polymarket/clob/funxyz/FunxyzConfigTest.java`
- Modify: `src/main/java/com/polymarket/clob/funxyz/FunxyzConfig.java`

`FunxyzConfig` 是不可变 record，行为非常薄；新增字段是机械重构，先改 test 让它失败，再改 record 让它绿。

### - [ ] Step 1: 把 `FunxyzConfigTest` 改成新预期（红）

完整替换 `src/test/java/com/polymarket/clob/funxyz/FunxyzConfigTest.java` 内容为：

```java
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
        assertThat(cfg.apiKey()).isNull();
        assertThat(cfg.httpClient()).isNotNull();
        assertThat(cfg.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(cfg.flagsConfigUrl())
                .isEqualTo(URI.create("https://sdk-cdn.fun.xyz/flags/v0/config.json"));
        assertThat(cfg.flagsRequestTimeout()).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void overridesAreHonored() {
        URI customUrl = URI.create("http://localhost:18080");
        HttpClient customHttp = HttpClient.newHttpClient();
        Duration customTimeout = Duration.ofSeconds(3);
        URI customFlagsUrl = URI.create("http://localhost:19090/flags.json");
        Duration customFlagsTimeout = Duration.ofMillis(750);

        FunxyzConfig cfg = FunxyzConfig.builder()
                .baseUrl(customUrl)
                .apiKey("my-key")
                .httpClient(customHttp)
                .requestTimeout(customTimeout)
                .flagsConfigUrl(customFlagsUrl)
                .flagsRequestTimeout(customFlagsTimeout)
                .build();

        assertThat(cfg.baseUrl()).isSameAs(customUrl);
        assertThat(cfg.apiKey()).isEqualTo("my-key");
        assertThat(cfg.httpClient()).isSameAs(customHttp);
        assertThat(cfg.requestTimeout()).isEqualTo(customTimeout);
        assertThat(cfg.flagsConfigUrl()).isSameAs(customFlagsUrl);
        assertThat(cfg.flagsRequestTimeout()).isEqualTo(customFlagsTimeout);
    }

    @Test
    void publicConstantsExposed() {
        assertThat(FunxyzConfig.DEFAULT_PUBLIC_API_KEY)
                .isEqualTo("Y53dikxXdT4E3afI1l8BMBSWgyhKvf65k6Dut1k6");
        assertThat(FunxyzConfig.DEFAULT_BASE_URL)
                .isEqualTo(URI.create("https://api.fun.xyz"));
        assertThat(FunxyzConfig.DEFAULT_FLAGS_CONFIG_URL)
                .isEqualTo(URI.create("https://sdk-cdn.fun.xyz/flags/v0/config.json"));
    }
}
```

### - [ ] Step 2: 跑测试确认编译失败

Run: `mvn -q test -Dtest=FunxyzConfigTest`

Expected: 编译失败 — `cannot find symbol: method flagsConfigUrl()`、`flagsRequestTimeout()`、`DEFAULT_FLAGS_CONFIG_URL` 等。

### - [ ] Step 3: 更新 `FunxyzConfig.java` 实现

完整替换 `src/main/java/com/polymarket/clob/funxyz/FunxyzConfig.java` 内容为：

```java
package com.polymarket.clob.funxyz;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * {@link FunxyzClient} 的不可变配置。
 *
 * <p>{@code apiKey} 默认 {@code null}。{@code null} 表示 "未显式设置"，由
 * {@link FunxyzClient} 在构造时从 {@link #DEFAULT_FLAGS_CONFIG_URL} 拉取并解析。
 * CDN 不可达时回退到 {@link #DEFAULT_PUBLIC_API_KEY}。如果调用方显式调用
 * {@link Builder#apiKey(String)}，则跳过 CDN 直接使用该值。</p>
 */
public record FunxyzConfig(
        URI baseUrl,
        String apiKey,
        HttpClient httpClient,
        Duration requestTimeout,
        URI flagsConfigUrl,
        Duration flagsRequestTimeout
) {

    /** 默认 fun.xyz API 入口。 */
    public static final URI DEFAULT_BASE_URL = URI.create("https://api.fun.xyz");

    /** 默认 fun.xyz 前端特性开关 CDN 端点。 */
    public static final URI DEFAULT_FLAGS_CONFIG_URL =
            URI.create("https://sdk-cdn.fun.xyz/flags/v0/config.json");

    /**
     * CDN 拉取失败时的兜底 apiKey。
     * 来源：polymarket.com 前端硬编码字面量（HAR 反向工程获得，非用户私密）。
     * 如失效，重新抓包替换或调用方传入自己的 key。
     */
    public static final String DEFAULT_PUBLIC_API_KEY = "Y53dikxXdT4E3afI1l8BMBSWgyhKvf65k6Dut1k6";

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private URI baseUrl = DEFAULT_BASE_URL;
        private String apiKey = null;
        private HttpClient httpClient;
        private Duration requestTimeout = Duration.ofSeconds(10);
        private URI flagsConfigUrl = DEFAULT_FLAGS_CONFIG_URL;
        private Duration flagsRequestTimeout = Duration.ofSeconds(4);

        public Builder baseUrl(URI v) { this.baseUrl = v; return this; }
        public Builder apiKey(String v) { this.apiKey = v; return this; }
        public Builder httpClient(HttpClient v) { this.httpClient = v; return this; }
        public Builder requestTimeout(Duration v) { this.requestTimeout = v; return this; }
        public Builder flagsConfigUrl(URI v) { this.flagsConfigUrl = v; return this; }
        public Builder flagsRequestTimeout(Duration v) { this.flagsRequestTimeout = v; return this; }

        public FunxyzConfig build() {
            HttpClient hc = httpClient != null ? httpClient : HttpClient.newHttpClient();
            return new FunxyzConfig(baseUrl, apiKey, hc, requestTimeout,
                                    flagsConfigUrl, flagsRequestTimeout);
        }
    }
}
```

### - [ ] Step 4: 跑测试确认绿

Run: `mvn -q test -Dtest=FunxyzConfigTest`

Expected: 3 tests pass。

### - [ ] Step 5: 提交

```bash
git add src/main/java/com/polymarket/clob/funxyz/FunxyzConfig.java \
        src/test/java/com/polymarket/clob/funxyz/FunxyzConfigTest.java
git commit -m "refactor(funxyz): FunxyzConfig 加 flagsConfigUrl / flagsRequestTimeout 字段

apiKey 默认值从 DEFAULT_PUBLIC_API_KEY 改为 null,语义变更为
'未显式设置 = 由 client 从 CDN 解析,失败时再回退到 DEFAULT_PUBLIC_API_KEY'.
新增 DEFAULT_FLAGS_CONFIG_URL 常量,默认 4s 拉取超时."
```

---

## Task 2: `FunxyzClient` 引入 `resolvedApiKey` 字段（仅短路分支，CDN 路径占位）

**Files:**
- Modify: `src/main/java/com/polymarket/clob/funxyz/FunxyzClient.java`

这一步是重构：把 header 取值点从 `cfg.apiKey()` 切换到实例字段 `resolvedApiKey`。CDN 拉取逻辑不写，先让 `cfg.apiKey() == null` 时直接回退到 `DEFAULT_PUBLIC_API_KEY`。现有 `FunxyzClientTest` 全部传了 `.apiKey("test-key-123")`，是这一步的安全网。

### - [ ] Step 1: 跑现有 `FunxyzClientTest` 拿到当前 baseline

Run: `mvn -q test -Dtest=FunxyzClientTest`

Expected: 全部 12 个 test 通过。

### - [ ] Step 2: 编辑 `FunxyzClient.java`

在 `src/main/java/com/polymarket/clob/funxyz/FunxyzClient.java` 中做 4 处修改：

**A. 加字段（在 `private final ObjectMapper mapper;` 那行下面）：**

```java
private final FunxyzConfig cfg;
private final ObjectMapper mapper;
private final String resolvedApiKey;  // 新增
```

**B. 修改构造函数：**

```java
public FunxyzClient(FunxyzConfig cfg) {
    this.cfg = Objects.requireNonNull(cfg, "cfg");
    this.mapper = JsonCodec.objectMapper();
    this.resolvedApiKey = resolveApiKey(cfg);
}
```

**C. 在文件末尾、`truncate(...)` 方法上面加私有方法（CDN 分支先占位为 fallback）：**

```java
/**
 * 构造时一次性解析 x-api-key:
 *   - 调用方显式 {@code cfg.apiKey() != null} → 直接使用,不发 CDN 请求
 *   - 否则 → 从 {@code cfg.flagsConfigUrl()} 拉取并解析
 *           {@code flags.token_transfer_source_chains_and_assets.overrides[0].if_any[0].values[0]}
 *   - CDN 任何失败 → warn 日志 + 回退 {@link FunxyzConfig#DEFAULT_PUBLIC_API_KEY}
 *
 * <p>本方法永不抛异常,保证 {@link FunxyzClient} 构造永远成功(除 {@code cfg == null})。</p>
 */
private String resolveApiKey(FunxyzConfig cfg) {
    if (cfg.apiKey() != null) {
        return cfg.apiKey();
    }
    // CDN 路径占位:Task 3 实现真实拉取逻辑。
    return FunxyzConfig.DEFAULT_PUBLIC_API_KEY;
}
```

**D. `getDepositAddresses(...)` 中 header 改用 `resolvedApiKey`：**

把:

```java
.header("x-api-key", cfg.apiKey())
```

改成:

```java
.header("x-api-key", resolvedApiKey)
```

### - [ ] Step 3: 跑现有测试确认仍绿

Run: `mvn -q test -Dtest=FunxyzClientTest`

Expected: 全部 12 个 test 通过（因为 `@BeforeEach` 显式传了 `.apiKey("test-key-123")`，走 short-circuit 分支，行为完全等价）。

### - [ ] Step 4: 跑 `FunxyzConfigTest` 确认没回归

Run: `mvn -q test -Dtest=FunxyzConfigTest`

Expected: 3 tests pass。

### - [ ] Step 5: 提交

```bash
git add src/main/java/com/polymarket/clob/funxyz/FunxyzClient.java
git commit -m "refactor(funxyz): FunxyzClient 引入 resolvedApiKey 字段

构造函数末尾调 resolveApiKey(cfg) 把 header 值固化到 final 字段;
/v1/eoa request 改用 resolvedApiKey 替代 cfg.apiKey().
当前 CDN 路径占位为直接回退 DEFAULT_PUBLIC_API_KEY,Task 3 实现真实拉取."
```

---

## Task 3: CDN happy path TDD（实现真实拉取逻辑）

**Files:**
- Create: `src/test/java/com/polymarket/clob/funxyz/FunxyzClientCdnResolveTest.java`
- Modify: `src/main/java/com/polymarket/clob/funxyz/FunxyzClient.java`

新建独立测试类专门覆盖 CDN 解析行为（区别于 `FunxyzClientTest` 测 `/v1/eoa` 业务）。先写 happy path 测试 → 失败 → 实现 CDN GET + Jackson 解析 → 绿。

### - [ ] Step 1: 写失败的 happy path 测试

创建 `src/test/java/com/polymarket/clob/funxyz/FunxyzClientCdnResolveTest.java`：

```java
package com.polymarket.clob.funxyz;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class FunxyzClientCdnResolveTest {

    private static final Address EOA = Address.fromHex("0x5f0fE47194FAC5FdE131C58359b614F520Db1342");
    private static final Address RECIPIENT = Address.fromHex("0xB51b3627E851EdeaFD81792F012C066805b6dFdE");

    private static final String EOA_OK_BODY = """
            {"depositAddr":"0x4C741213d8519429002ab3E69DE9620fb9b48C69",
             "solanaAddr":"x","tronAddr":"T","btcAddrSegwit":"bc1q",
             "blocked":false}
            """;

    /** 真实抓样的最小化骨架,只保留我们要走的 JSON 路径。 */
    private static final String FLAGS_OK_BODY = """
            {
              "flags": {
                "token_transfer_source_chains_and_assets": {
                  "type": "string",
                  "default_value": "{}",
                  "overrides": [
                    {
                      "if_any": [
                        { "key": "apiKey", "type": "isAnyOf",
                          "values": ["Y53dikxXdT4E3afI1l8BMBSWgyhKvf65k6Dut1k6"] }
                      ],
                      "value": "{}"
                    }
                  ]
                }
              }
            }
            """;

    private WireMockServer server;

    @BeforeEach
    void start() {
        server = new WireMockServer(0);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    /** 给定 stub 与可选超时配置,构造一个走"未显式 apiKey"分支的 client。 */
    private FunxyzClient buildClientWithoutExplicitApiKey(Duration flagsTimeout) {
        FunxyzConfig.Builder b = FunxyzConfig.builder()
                .baseUrl(URI.create(server.baseUrl()))
                .flagsConfigUrl(URI.create(server.baseUrl() + "/flags/v0/config.json"));
        if (flagsTimeout != null) {
            b.flagsRequestTimeout(flagsTimeout);
        }
        // 注意:不调 .apiKey(),保留 null,触发 CDN 路径
        return new FunxyzClient(b.build());
    }

    @Test
    void cdnHappyPath_usesFirstApiKeyFromOverrides() throws Exception {
        server.stubFor(get(urlEqualTo("/flags/v0/config.json"))
                .willReturn(okJson(FLAGS_OK_BODY)));
        server.stubFor(post(urlEqualTo("/v1/eoa")).willReturn(okJson(EOA_OK_BODY)));

        FunxyzClient client = buildClientWithoutExplicitApiKey(null);
        client.getDepositAddresses(EOA, RECIPIENT).get();

        server.verify(getRequestedFor(urlEqualTo("/flags/v0/config.json")));
        server.verify(postRequestedFor(urlEqualTo("/v1/eoa"))
                .withHeader("x-api-key",
                        equalTo("Y53dikxXdT4E3afI1l8BMBSWgyhKvf65k6Dut1k6")));
    }
}
```

### - [ ] Step 2: 跑测试确认失败

Run: `mvn -q test -Dtest=FunxyzClientCdnResolveTest`

Expected: `cdnHappyPath_usesFirstApiKeyFromOverrides` 失败 — header 值是 `DEFAULT_PUBLIC_API_KEY` 字面量（恰好等于 `Y53di...`，所以 header 比对会通过），但 `/flags/v0/config.json` 0 次请求，`server.verify(getRequestedFor(...))` 失败。

> 注：由于 `DEFAULT_PUBLIC_API_KEY == "Y53di..."` 恰好和测试 stub 第一个 override 的 value 相同（这是设计预期：CDN 第一名就是这个 key），所以 header 断言会假绿。`server.verify(getRequestedFor(...))` 是这个测试的真正"红"信号 — 它强制 client 必须真去拉 CDN，不只是凑巧返回了同一个值。

### - [ ] Step 3: 实现 CDN 拉取逻辑

在 `src/main/java/com/polymarket/clob/funxyz/FunxyzClient.java` 顶部 import 加（如果尚未存在）：

```java
import com.fasterxml.jackson.databind.JsonNode;
```

然后把 Task 2 占位的 `resolveApiKey` 方法**完整替换**为：

```java
private String resolveApiKey(FunxyzConfig cfg) {
    if (cfg.apiKey() != null) {
        return cfg.apiKey();
    }
    try {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(cfg.flagsConfigUrl())
                .timeout(cfg.flagsRequestTimeout())
                .GET()
                .build();
        HttpResponse<String> resp = cfg.httpClient()
                .send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException(
                    "funxyz CDN returned status " + resp.statusCode());
        }
        JsonNode root = mapper.readTree(resp.body());
        String key = root.path("flags")
                         .path("token_transfer_source_chains_and_assets")
                         .path("overrides").path(0)
                         .path("if_any").path(0)
                         .path("values").path(0)
                         .asText("");
        if (key.isEmpty()) {
            throw new IllegalStateException("funxyz CDN apiKey path missing or empty");
        }
        log.info("funxyz CDN apiKey resolved (length={})", key.length());
        return key;
    } catch (Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        log.warn("funxyz CDN apiKey resolve failed: {}, falling back to DEFAULT_PUBLIC_API_KEY",
                e.toString());
        return FunxyzConfig.DEFAULT_PUBLIC_API_KEY;
    }
}
```

需要确认 `FunxyzClient.java` 顶部已有这些 import（旧文件已有前 4 个）：

```java
import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
```

### - [ ] Step 4: 跑测试确认绿

Run: `mvn -q test -Dtest=FunxyzClientCdnResolveTest`

Expected: `cdnHappyPath_usesFirstApiKeyFromOverrides` PASS（GET `/flags/v0/config.json` 被调一次）。

### - [ ] Step 5: 跑完整测试套确认无回归

Run: `mvn -q test -Dtest='FunxyzConfigTest,FunxyzClientTest,FunxyzClientCdnResolveTest'`

Expected: 全部 16 个 test pass（3 + 12 + 1）。

### - [ ] Step 6: 提交

```bash
git add src/main/java/com/polymarket/clob/funxyz/FunxyzClient.java \
        src/test/java/com/polymarket/clob/funxyz/FunxyzClientCdnResolveTest.java
git commit -m "feat(funxyz): FunxyzClient 启动时从 sdk-cdn 解析 apiKey

构造时 GET cfg.flagsConfigUrl(),Jackson path() 链式取
flags.token_transfer_source_chains_and_assets.overrides[0].if_any[0].values[0],
非空字符串作为 x-api-key header.失败时 catch 到任何 Exception
都回退到 DEFAULT_PUBLIC_API_KEY,构造永不抛.

新增 FunxyzClientCdnResolveTest 覆盖 CDN happy path."
```

---

## Task 4: CDN 失败矩阵测试

**Files:**
- Modify: `src/test/java/com/polymarket/clob/funxyz/FunxyzClientCdnResolveTest.java`

加 5 个测试覆盖 spec §3.2 失败矩阵（HTTP 非 200、超时、非 JSON、JSON 缺路径、values 空数组）。所有用例都期望 header 等于 `DEFAULT_PUBLIC_API_KEY`。Task 3 实现里 `catch (Exception e)` 已经覆盖所有失败分支，这 5 个测试是在 lock down 行为，预期一次性全绿。

### - [ ] Step 1: 在 `FunxyzClientCdnResolveTest` 末尾追加 5 个测试

在 `FunxyzClientCdnResolveTest` class 内（最后一个 `}` 前）加：

```java
@Test
void cdn500_fallsBackToDefaultPublicApiKey() throws Exception {
    server.stubFor(get(urlEqualTo("/flags/v0/config.json"))
            .willReturn(aResponse().withStatus(500).withBody("oops")));
    server.stubFor(post(urlEqualTo("/v1/eoa")).willReturn(okJson(EOA_OK_BODY)));

    FunxyzClient client = buildClientWithoutExplicitApiKey(null);
    client.getDepositAddresses(EOA, RECIPIENT).get();

    server.verify(postRequestedFor(urlEqualTo("/v1/eoa"))
            .withHeader("x-api-key",
                    equalTo(FunxyzConfig.DEFAULT_PUBLIC_API_KEY)));
}

@Test
void cdnTimeout_fallsBackToDefaultPublicApiKey() throws Exception {
    // WireMock 固定延迟 2s,client 超时 200ms → HttpTimeoutException
    server.stubFor(get(urlEqualTo("/flags/v0/config.json"))
            .willReturn(okJson(FLAGS_OK_BODY).withFixedDelay(2_000)));
    server.stubFor(post(urlEqualTo("/v1/eoa")).willReturn(okJson(EOA_OK_BODY)));

    FunxyzClient client = buildClientWithoutExplicitApiKey(Duration.ofMillis(200));
    client.getDepositAddresses(EOA, RECIPIENT).get();

    server.verify(postRequestedFor(urlEqualTo("/v1/eoa"))
            .withHeader("x-api-key",
                    equalTo(FunxyzConfig.DEFAULT_PUBLIC_API_KEY)));
}

@Test
void cdnNonJsonBody_fallsBackToDefaultPublicApiKey() throws Exception {
    server.stubFor(get(urlEqualTo("/flags/v0/config.json"))
            .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("content-type", "application/json")
                    .withBody("not json at all {")));
    server.stubFor(post(urlEqualTo("/v1/eoa")).willReturn(okJson(EOA_OK_BODY)));

    FunxyzClient client = buildClientWithoutExplicitApiKey(null);
    client.getDepositAddresses(EOA, RECIPIENT).get();

    server.verify(postRequestedFor(urlEqualTo("/v1/eoa"))
            .withHeader("x-api-key",
                    equalTo(FunxyzConfig.DEFAULT_PUBLIC_API_KEY)));
}

@Test
void cdnJsonMissingPath_fallsBackToDefaultPublicApiKey() throws Exception {
    server.stubFor(get(urlEqualTo("/flags/v0/config.json"))
            .willReturn(okJson("{\"flags\":{}}")));
    server.stubFor(post(urlEqualTo("/v1/eoa")).willReturn(okJson(EOA_OK_BODY)));

    FunxyzClient client = buildClientWithoutExplicitApiKey(null);
    client.getDepositAddresses(EOA, RECIPIENT).get();

    server.verify(postRequestedFor(urlEqualTo("/v1/eoa"))
            .withHeader("x-api-key",
                    equalTo(FunxyzConfig.DEFAULT_PUBLIC_API_KEY)));
}

@Test
void cdnValuesEmptyArray_fallsBackToDefaultPublicApiKey() throws Exception {
    String emptyValuesBody = """
            {"flags":{"token_transfer_source_chains_and_assets":{
              "overrides":[{"if_any":[{"key":"apiKey","values":[]}]}]
            }}}
            """;
    server.stubFor(get(urlEqualTo("/flags/v0/config.json"))
            .willReturn(okJson(emptyValuesBody)));
    server.stubFor(post(urlEqualTo("/v1/eoa")).willReturn(okJson(EOA_OK_BODY)));

    FunxyzClient client = buildClientWithoutExplicitApiKey(null);
    client.getDepositAddresses(EOA, RECIPIENT).get();

    server.verify(postRequestedFor(urlEqualTo("/v1/eoa"))
            .withHeader("x-api-key",
                    equalTo(FunxyzConfig.DEFAULT_PUBLIC_API_KEY)));
}
```

### - [ ] Step 2: 跑这 5 个新测试

Run: `mvn -q test -Dtest=FunxyzClientCdnResolveTest`

Expected: 全部 6 个 test pass（happy path + 5 个失败回退）。

> 如果 `cdnTimeout_fallsBackToDefaultPublicApiKey` 因 WireMock fixedDelay 行为不稳定而 flaky，把延迟从 2000ms 提到 5000ms（仍远大于 200ms 超时）即可。这是已知 WireMock 延迟模拟的 jitter 容忍调整，不要换成毫秒级延迟。

### - [ ] Step 3: 跑完整测试套确认无回归

Run: `mvn -q test -Dtest='FunxyzConfigTest,FunxyzClientTest,FunxyzClientCdnResolveTest'`

Expected: 全部 20 个 test pass（3 + 12 + 6）。

### - [ ] Step 4: 提交

```bash
git add src/test/java/com/polymarket/clob/funxyz/FunxyzClientCdnResolveTest.java
git commit -m "test(funxyz): CDN apiKey 解析失败矩阵全覆盖

5 个 case: HTTP 500 / 超时 / 非 JSON body / JSON 缺路径 / values 空数组,
全部期望回退到 DEFAULT_PUBLIC_API_KEY,构造不抛."
```

---

## Task 5: 显式 apiKey 跳过 CDN 测试（验证不发请求）

**Files:**
- Modify: `src/test/java/com/polymarket/clob/funxyz/FunxyzClientCdnResolveTest.java`

加最后一个测试：当调用方传 `.apiKey(...)` 时，client 不应访问 `/flags/...`。Task 2 的 short-circuit 已经实现了这个行为，这一步是为它加防护测试。

### - [ ] Step 1: 在 `FunxyzClientCdnResolveTest` 末尾追加测试

在 class 内最后一个 `}` 前加：

```java
@Test
void explicitApiKey_skipsCdnEntirely() throws Exception {
    // 故意 stub /flags/... 但用 verify(0) 断言不会被命中
    server.stubFor(get(urlEqualTo("/flags/v0/config.json"))
            .willReturn(okJson(FLAGS_OK_BODY)));
    server.stubFor(post(urlEqualTo("/v1/eoa")).willReturn(okJson(EOA_OK_BODY)));

    FunxyzClient client = new FunxyzClient(FunxyzConfig.builder()
            .baseUrl(URI.create(server.baseUrl()))
            .flagsConfigUrl(URI.create(server.baseUrl() + "/flags/v0/config.json"))
            .apiKey("explicit-key-xyz")
            .build());
    client.getDepositAddresses(EOA, RECIPIENT).get();

    server.verify(0, getRequestedFor(urlEqualTo("/flags/v0/config.json")));
    server.verify(postRequestedFor(urlEqualTo("/v1/eoa"))
            .withHeader("x-api-key", equalTo("explicit-key-xyz")));
}
```

### - [ ] Step 2: 跑测试确认绿

Run: `mvn -q test -Dtest=FunxyzClientCdnResolveTest`

Expected: 全部 7 个 test pass。

### - [ ] Step 3: 跑全部 funxyz 包测试

Run: `mvn -q test -Dtest='com.polymarket.clob.funxyz.*Test'`

Expected: 全部 21 个 test pass（3 + 12 + 7）。注意排除 `FunxyzClientIntegrationTest`，它是 env-gated（默认关）。

### - [ ] Step 4: 跑完整 mvn test 确保无跨包回归

Run: `mvn -q test`

Expected: 整个项目测试通过。

### - [ ] Step 5: 提交

```bash
git add src/test/java/com/polymarket/clob/funxyz/FunxyzClientCdnResolveTest.java
git commit -m "test(funxyz): 显式 apiKey 跳过 CDN 不访问 /flags/... 端点"
```

---

## Task 6: 手动冒烟验证 + 收尾

**Files:** （仅运行命令，不改代码）

验证生产 CDN 端点真的能拉到、日志输出符合预期。这一步会发真实出站 HTTP 请求。如果在受限网络下，可以跳过，依赖 `FunxyzClientIntegrationTest` 在自己机器上 env-gated 跑。

### - [ ] Step 1: 写一个临时验证脚本

创建 `/tmp/FunxyzCdnSmoke.java`（项目外，验证完删除）：

```java
import com.polymarket.clob.funxyz.FunxyzClient;
import com.polymarket.clob.funxyz.FunxyzConfig;

public class FunxyzCdnSmoke {
    public static void main(String[] args) {
        // 不调 .apiKey() → 走 CDN 路径
        FunxyzClient client = new FunxyzClient(FunxyzConfig.builder().build());
        System.out.println("client constructed OK; check log for 'funxyz CDN apiKey resolved'");
    }
}
```

### - [ ] Step 2: 用 mvn 跑这段冒烟（同步、无网络拒绝）

Run:
```bash
mvn -q compile && \
mvn -q exec:java -Dexec.mainClass=FunxyzCdnSmoke \
    -Dexec.classpathScope=compile \
    -Dexec.cleanupDaemonThreads=false 2>&1 | grep -i funxyz
```

如果项目没装 `exec-maven-plugin`，跳过并改用以下临时方式：把上述 `main()` 内容粘到任意一个项目内 example（如 `FullOnboardAndTradeExample.java`）的 `main(...)` 第一行临时跑一次，看到日志后撤回这一行。或者直接执行 §Step 3 的 integration test。

Expected stdout/log（任一种成功表现）：
- `funxyz CDN apiKey resolved (length=40)` — CDN 命中
- 或 `funxyz CDN apiKey resolve failed: ..., falling back to DEFAULT_PUBLIC_API_KEY` — CDN 不可达，fallback 成功

两种都说明逻辑工作正常。

### - [ ] Step 3: （可选，需联网）跑 integration test 验证端到端

Run: `FUNXYZ_IT=true mvn -q test -Dtest=FunxyzClientIntegrationTest`

Expected: 集成测过；日志里能看到 `funxyz CDN apiKey resolved (length=40)` 一行。

### - [ ] Step 4: 删掉临时验证文件（如果创建过）

```bash
rm -f /tmp/FunxyzCdnSmoke.java
```

不需要 commit（步骤 1 创建的文件在 `/tmp/` 不在 repo）。

### - [ ] Step 5: （仅当 Step 2 / 3 失败）回滚或修复

如果冒烟失败（不是 fallback 而是抛了未处理异常），说明 `resolveApiKey` 的 `catch (Exception e)` 漏了某种异常（如 `Error` 或非 `Exception` 子类）。检查异常类型，把 `catch (Exception e)` 扩到 `catch (Throwable t)`（重新评估利弊后再做这个改动）。如果 Step 2 / 3 通过，本任务无需 commit。

---

## Self-Review

**Spec 覆盖检查（逐项对应）：**

| Spec 条目 | 实现位置 |
|---|---|
| §1.2 包含: FunxyzConfig 加 2 字段、Builder.apiKey 默认 null | Task 1 |
| §1.2 包含: FunxyzClient 构造时同步解析、header 改 resolvedApiKey | Task 2 (骨架) + Task 3 (CDN 拉取) |
| §1.2 包含: FunxyzConfigTest 默认值断言更新 | Task 1 Step 1 |
| §1.2 包含: FunxyzClientTest 增加 3 类用例 | Task 3 (CDN happy) + Task 4 (失败矩阵) + Task 5 (显式跳 CDN) |
| §1.3 Q1 拉取职责: FunxyzClient 内部构造时同步 | Task 3 Step 3 (resolveApiKey 实现) |
| §1.3 Q3 取 [0][0][0] | Task 3 Step 3 (Jackson path 链) |
| §1.3 Q4 优先级 调用方 > CDN > DEFAULT | Task 2 Step 2-C (short-circuit) + Task 3 Step 3 (CDN + fallback) |
| §1.3 Q7 4s 超时 | Task 1 Step 3 (Builder 默认 `Duration.ofSeconds(4)`) |
| §1.3 Q8 flagsConfigUrl 公开可注入 | Task 1 Step 3 (record + Builder 公开方法) |
| §3.1 行为契约伪代码 | Task 3 Step 3 完整实现 |
| §3.2 失败矩阵 7 种情形 | Task 4 (5 个测试覆盖 5 个分支)；JSON 路径任一节点缺失 / overrides 空数组 / values[0] 不是字符串这 3 种共享同一个 `key.isEmpty()` 判断，由 Task 4 中 cdnJsonMissingPath / cdnValuesEmptyArray 两个测试代表 |
| §3.3 日志: 成功 info、失败 warn 不打 key | Task 3 Step 3 (`length=` 不打 key 本身) |
| §4.1 FunxyzConfig 完整代码 | Task 1 Step 3 |
| §4.2 FunxyzClient 完整代码 + InterruptedException 处理 | Task 3 Step 3 (`Thread.currentThread().interrupt()` 已写入) |
| §5.1 7 个测试用例 | Task 3 (#1) + Task 4 (#3-7) + Task 5 (#2)，编号已逐条对应 |
| §5.2 FunxyzConfigTest 改动 | Task 1 Step 1 |
| §5.3 现有 FunxyzClientTest 无需改动 | Task 2 Step 3 验证仍绿，未改动该文件 |
| §5.4 IntegrationTest 保持原状 | 不在 plan 改动列表，Task 6 Step 3 用作可选验证 |

**未在 plan 中显式建任务的 spec 条目：**
- §6 已主动拒绝项 — 这些是显式不做，无需任务
- §7 风险与缓解 — 是上下文背景，由实现自然规避（catch Exception、4s 超时、flagsConfigUrl 注入）
- §8 实施顺序建议 — plan 的 Task 1-6 已按此顺序展开

**Placeholder 扫描：**
- 无 "TBD/TODO/implement later"
- 无 "add appropriate error handling" — 失败矩阵 5 个 case 是显式行为
- Task 6 Step 5 写明了"仅当冒烟失败才执行"，不是 placeholder
- 所有代码块都是完整代码（FunxyzConfig 整个文件、resolveApiKey 整个方法、每个测试整个方法）

**类型一致性检查：**
- `flagsConfigUrl: URI` / `flagsRequestTimeout: Duration` 在 Task 1（record + Builder）和 Task 3、4、5（测试 builder 调用）保持一致
- `resolvedApiKey: String final` 在 Task 2 字段定义、Task 2 构造函数赋值、Task 2 header 引用、Task 3 实现里返回 String 全部一致
- `DEFAULT_PUBLIC_API_KEY: String`、`DEFAULT_FLAGS_CONFIG_URL: URI` 在 Task 1 定义和 Task 3-5 测试断言里类型一致
- 测试方法签名统一 `throws Exception`、统一用 `assertThat(...)`、统一 `getDepositAddresses(EOA, RECIPIENT).get()` 调用模式
- WireMock import 与现有 `FunxyzClientTest` 完全一致（`com.github.tomakehurst.wiremock.*`）

**风险点确认：**
- Task 4 Step 2 提示了 WireMock fixedDelay 抖动的处理建议（提到 5s）
- Task 6 提示了无 `exec-maven-plugin` 时的降级方案

无问题，plan 准备就绪。
