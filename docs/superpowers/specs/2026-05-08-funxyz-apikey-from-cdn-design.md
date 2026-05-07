# FunxyzClient apiKey 启动时从 sdk-cdn 解析

> **状态**：设计稿，待实现
> **作者**：taka@noahbase.com
> **日期**：2026-05-08
> **关联**：
> - 现状：`src/main/java/com/polymarket/clob/funxyz/FunxyzConfig.java:28` 硬编码 `DEFAULT_PUBLIC_API_KEY = "Y53dikxXdT4E3afI1l8BMBSWgyhKvf65k6Dut1k6"`
> - 上游事实源：`GET https://sdk-cdn.fun.xyz/flags/v0/config.json` 是 fun.xyz 前端 SDK 的特性开关分发端点，结构稳定
> - 路径：`flags.token_transfer_source_chains_and_assets.overrides[0].if_any[0].values[0]`

---

## 1. 目标与范围

### 1.1 目标

让 `FunxyzClient` 的 `x-api-key` header 不再依赖编译期硬编码常量，改为构造时一次性从 fun.xyz 公开 CDN 拉取并解析，CDN 不可达时降级到现有常量。调用方仍可显式覆盖。

### 1.2 范围

**包含：**

- `FunxyzConfig.java` 调整：Builder.apiKey 默认值改 `null`；新增 `flagsConfigUrl` / `flagsRequestTimeout` 两个可注入字段
- `FunxyzClient.java` 调整：构造函数末尾同步解析 apiKey；request header 从 `cfg.apiKey()` 改成实例字段 `resolvedApiKey`
- `FunxyzConfigTest` 默认值断言更新（旧断言 `cfg.apiKey() == DEFAULT_PUBLIC_API_KEY` 改为 `cfg.apiKey() == null`，并新增两个新字段断言）
- `FunxyzClientTest` 增加 3 类用例：CDN happy path、调用方传 apiKey 跳过 CDN、CDN 各种失败回退到 DEFAULT

**不包含：**

- 异步/懒拉取、TTL 刷新、后台定时任务
- 多 apiKey 轮询、配额分流、按 IP 国家/userId 选 key
- 引入 `Supplier<String>` / `KeyResolver` SPI 注入
- 解析 `overrides[].if_any[]` 里其他维度（仅走 `[0].if_any[0].values[0]` 一条路径）
- 修改 `DEFAULT_PUBLIC_API_KEY` 常量字面量本身

### 1.3 关键决策摘要

| # | 决策 | 选择 |
|---|---|---|
| Q1 | 拉取职责归谁 | `FunxyzClient` 内部，构造时同步执行 |
| Q2 | 拉取时机 | 构造一次，实例生命周期内不刷新 |
| Q3 | 取哪个值 | `overrides[0].if_any[0].values[0]` —— 第一个 |
| Q4 | 优先级 | 调用方显式 `builder.apiKey(...)` > CDN > `DEFAULT_PUBLIC_API_KEY` |
| Q5 | "调用方显式"如何识别 | Builder.apiKey 默认值改 `null`，非 null 即视为显式 |
| Q6 | CDN 失败行为 | warn 日志 + 回退到 `DEFAULT_PUBLIC_API_KEY`，构造**不**抛异常 |
| Q7 | CDN 拉取超时 | 独立字段，默认 4 秒；不复用 `cfg.requestTimeout()`（10s 太长） |
| Q8 | CDN URL 是否可注入 | 是，公开 builder 字段 `flagsConfigUrl`，便于单测和未来切环境 |

---

## 2. 协议事实

### 2.1 端点

```
GET https://sdk-cdn.fun.xyz/flags/v0/config.json
无鉴权，公开 CDN
返回 JSON Content-Type: application/json
```

### 2.2 提取路径

```
.flags
  .token_transfer_source_chains_and_assets
    .overrides[0]
      .if_any[0]
        .values[0]
```

类型必须是字符串且非空。

### 2.3 抓样响应骨架（截取相关部分）

```json
{
  "flags": {
    "token_transfer_source_chains_and_assets": {
      "type": "string",
      "default_value": "...",
      "overrides": [
        {
          "if_any": [
            { "key": "apiKey", "type": "isAnyOf",
              "values": ["Y53dikxXdT4E3afI1l8BMBSWgyhKvf65k6Dut1k6"] }
          ],
          "value": "..."
        },
        // ... 共 9 个 override 条目，每条对应一个 polymarket 客户的链/资产白名单覆盖
      ]
    }
    // ... 其他 flag
  }
}
```

注：fun.xyz 这个端点本来是给前端 SDK 用的特性开关分发，每条 override 是 "若调用者 apiKey 等于 X，则该 flag 取 Y"。我们借用这个数据顺手拿到了 fun.xyz 公开发布的 polymarket apiKey 集合，取 `[0]` 即等价于"列表里第一个登记客户"。

---

## 3. 行为契约

### 3.1 构造时解析

```
new FunxyzClient(cfg) 同步执行：

  if (cfg.apiKey() != null) {
    resolvedApiKey = cfg.apiKey()                    // 调用方显式覆盖,不发 CDN 请求
  } else {
    try {
      GET cfg.flagsConfigUrl(),
          timeout = cfg.flagsRequestTimeout(),       // 4s 默认
          httpClient = cfg.httpClient()              // 复用
      解析 flags.token_transfer_source_chains_and_assets
            .overrides[0].if_any[0].values[0]
      若是非空字符串 → resolvedApiKey = 该值
      否则           → 抛内部异常进入 catch
    } catch (任何异常) {
      log.warn("funxyz CDN apiKey resolve failed, fallback to DEFAULT", e)
      resolvedApiKey = DEFAULT_PUBLIC_API_KEY
    }
  }
```

`resolvedApiKey` 是 `final` 字段，构造完成后不可变。后续每次 `getDepositAddresses(...)` 用 `resolvedApiKey` 作为 `x-api-key` header。

### 3.2 失败矩阵（仅 CDN 路径）

| 情形 | 行为 |
|---|---|
| 网络超时 / 连接拒绝 / DNS 失败 | warn 日志 + 回退 `DEFAULT_PUBLIC_API_KEY` |
| HTTP 状态码非 200 | warn 日志 + 回退 `DEFAULT_PUBLIC_API_KEY` |
| Response body 非合法 JSON | warn 日志 + 回退 `DEFAULT_PUBLIC_API_KEY` |
| JSON 路径任一节点缺失（如 `flags.token_transfer_source_chains_and_assets` 不存在） | warn 日志 + 回退 |
| `overrides` 是空数组 | warn 日志 + 回退 |
| `overrides[0].if_any[0].values` 是空数组 | warn 日志 + 回退 |
| 取出的值不是字符串 / 是空字符串 | warn 日志 + 回退 |

**关键不变量**：`new FunxyzClient(cfg)` 永远构造成功（除非 `cfg == null`）。CDN 不可用不是一个用户可见的错误。

### 3.3 日志级别

- 成功命中 CDN：`log.info("funxyz CDN apiKey resolved (length={})", key.length())`，**不打印 key 本身**
- 显式跳过 CDN（`cfg.apiKey() != null`）：不打印
- CDN 失败回退：`log.warn("funxyz CDN apiKey resolve failed: {}, falling back to DEFAULT_PUBLIC_API_KEY", reason)`，附 root cause

---

## 4. 接口变更

### 4.1 `FunxyzConfig`

```java
public record FunxyzConfig(
        URI baseUrl,
        String apiKey,                  // 语义变化: null = 未显式,从 CDN 解析
        HttpClient httpClient,
        Duration requestTimeout,
        URI flagsConfigUrl,             // 新增
        Duration flagsRequestTimeout    // 新增
) {
    public static final URI DEFAULT_BASE_URL =
            URI.create("https://api.fun.xyz");

    public static final URI DEFAULT_FLAGS_CONFIG_URL =
            URI.create("https://sdk-cdn.fun.xyz/flags/v0/config.json");

    /** CDN 失败兜底常量。语义从 "Builder 默认值" 改为 "兜底值"。 */
    public static final String DEFAULT_PUBLIC_API_KEY =
            "Y53dikxXdT4E3afI1l8BMBSWgyhKvf65k6Dut1k6";

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private URI baseUrl = DEFAULT_BASE_URL;
        private String apiKey = null;                                 // 变更: 原默认 = DEFAULT_PUBLIC_API_KEY
        private HttpClient httpClient;
        private Duration requestTimeout = Duration.ofSeconds(10);
        private URI flagsConfigUrl = DEFAULT_FLAGS_CONFIG_URL;        // 新增
        private Duration flagsRequestTimeout = Duration.ofSeconds(4); // 新增

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

### 4.2 `FunxyzClient`

新增不可变字段与解析逻辑（删节版示意，非最终代码）：

```java
public final class FunxyzClient {
    // ... 现有字段 ...
    private final String resolvedApiKey;

    public FunxyzClient(FunxyzConfig cfg) {
        this.cfg = Objects.requireNonNull(cfg, "cfg");
        this.mapper = JsonCodec.objectMapper();
        this.resolvedApiKey = resolveApiKey(cfg);
    }

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
                    .send(req, HttpResponse.BodyHandlers.ofString());  // 同步
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("status=" + resp.statusCode());
            }
            JsonNode root = mapper.readTree(resp.body());
            String key = root.path("flags")
                             .path("token_transfer_source_chains_and_assets")
                             .path("overrides").path(0)
                             .path("if_any").path(0)
                             .path("values").path(0)
                             .asText("");
            if (key.isEmpty()) {
                throw new IllegalStateException("apiKey path missing or empty");
            }
            log.info("funxyz CDN apiKey resolved (length={})", key.length());
            return key;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();   // 保持中断标志
            }
            log.warn("funxyz CDN apiKey resolve failed: {}, falling back to DEFAULT_PUBLIC_API_KEY",
                    e.toString());
            return FunxyzConfig.DEFAULT_PUBLIC_API_KEY;
        }
    }

    // getDepositAddresses(...) 中:
    //     .header("x-api-key", cfg.apiKey())
    //   改为:
    //     .header("x-api-key", resolvedApiKey)
}
```

`InterruptedException` 由 `httpClient.send(...)` 抛出，被外层 `catch (Exception e)` 捕获并落到回退分支；为保持 interrupted 语义，捕获后调用 `Thread.currentThread().interrupt()` 再回退。

---

## 5. 测试

沿用 WireMock，与现有 `FunxyzClientTest` 同一基础设施。

### 5.1 单测用例

| # | 用例 | stub 行为 | 期望 |
|---|---|---|---|
| 1 | CDN happy path | builder 不传 `apiKey`；stub `/flags/v0/config.json` 返回完整真实抓样 JSON | `/v1/eoa` header 等于 `Y53di...` |
| 2 | 调用方传 apiKey 跳过 CDN | builder.apiKey("test-key-123")；不 stub `/flags/...` | WireMock 验证 `/flags/...` 被请求 0 次；`/v1/eoa` header == "test-key-123" |
| 3 | CDN 状态码 500 回退 | builder 不传 `apiKey`；stub `/flags/...` 返回 500 | `/v1/eoa` header == `DEFAULT_PUBLIC_API_KEY` |
| 4 | CDN 超时回退 | stub `/flags/...` 加 2s 固定延迟，`flagsRequestTimeout` 设 200ms | header == `DEFAULT_PUBLIC_API_KEY`（不做时间断言，避免 CI 抖动） |
| 5 | CDN 返回非 JSON 回退 | stub `/flags/...` 返回 `"not json"` | header == `DEFAULT_PUBLIC_API_KEY` |
| 6 | CDN JSON 缺路径回退 | stub 返回 `{"flags":{}}` | header == `DEFAULT_PUBLIC_API_KEY` |
| 7 | CDN values 空数组回退 | stub 返回路径存在但 values 是 `[]` | header == `DEFAULT_PUBLIC_API_KEY` |

### 5.2 `FunxyzConfigTest` 改动

```java
@Test
void buildExposesAllDefaults() {
    FunxyzConfig cfg = FunxyzConfig.builder().build();

    assertThat(cfg.baseUrl()).isEqualTo(URI.create("https://api.fun.xyz"));
    assertThat(cfg.apiKey()).isNull();                                  // 变更
    assertThat(cfg.httpClient()).isNotNull();
    assertThat(cfg.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(cfg.flagsConfigUrl())                                    // 新增
            .isEqualTo(URI.create("https://sdk-cdn.fun.xyz/flags/v0/config.json"));
    assertThat(cfg.flagsRequestTimeout()).isEqualTo(Duration.ofSeconds(4));  // 新增
}

@Test
void publicConstantsExposed() {
    assertThat(FunxyzConfig.DEFAULT_PUBLIC_API_KEY)
            .isEqualTo("Y53dikxXdT4E3afI1l8BMBSWgyhKvf65k6Dut1k6");
    assertThat(FunxyzConfig.DEFAULT_BASE_URL)
            .isEqualTo(URI.create("https://api.fun.xyz"));
    assertThat(FunxyzConfig.DEFAULT_FLAGS_CONFIG_URL)                   // 新增
            .isEqualTo(URI.create("https://sdk-cdn.fun.xyz/flags/v0/config.json"));
}
```

`overridesAreHonored()` 对应也加 `.flagsConfigUrl(...)` / `.flagsRequestTimeout(...)` 的 builder 链与断言。

### 5.3 现有 `FunxyzClientTest` 影响

所有现有用例都用 `FunxyzConfig.builder().apiKey("test-key-123").build()` 构造，自动走 §3.1 中的"显式覆盖跳过 CDN"分支，`/flags/...` 不会被请求，**无需改动**。

### 5.4 `FunxyzClientIntegrationTest`

env-var gated 集成测保持原状（不显式传 apiKey 即触发真实 CDN 调用），可顺带验证生产 CDN 端点活着。

---

## 6. 已主动拒绝的设计（YAGNI）

| 拒绝项 | 理由 |
|---|---|
| `Supplier<String>` / `KeyResolver` SPI | 单测用 WireMock + `flagsConfigUrl` 注入足够，多一层抽象无收益 |
| TTL 刷新 / 后台定时任务 | 当前 fun.xyz 没有"key 中途轮换"的可观察证据；增加生命周期管理（shutdown）成本 |
| 多 apiKey 轮询、按 userId/ipCountry 选 key | 用户明确"取第一个" |
| 解析 `address_blacklist` / `blocked_countries` 等其他 flag | 超出本期范围，需要另立项 |
| 把 `flagsConfigUrl` / `flagsRequestTimeout` 做成静态可变 | 不可变 record 设计哲学一致性 |

---

## 7. 风险与缓解

| 风险 | 概率 | 缓解 |
|---|---|---|
| fun.xyz 修改 CDN JSON 结构（如重命名 `token_transfer_source_chains_and_assets`） | 中 | 解析失败立即回退到硬编码常量，不影响业务；warn 日志监控 |
| `overrides[0]` 顺序变化导致首位 key 漂移 | 中 | 这是预期行为（"取第一个"），不是 bug；如需稳定性需独立项 |
| 构造函数同步 IO 增加冷启动 ~100-2000ms | 低 | 4s 超时硬上限；调用方实例缓存 client 即可平摊 |
| WireMock 在 CI 端口冲突 | 低 | 现有测试用 `new WireMockServer(0)` 随机端口，沿用 |
| 生产 CDN DNS / TLS 异常导致 warn 日志噪声 | 低 | 回退路径仍可工作；如噪声大可后续把日志降到 debug |

---

## 8. 实施顺序建议（供 plan 阶段参考）

1. `FunxyzConfig` 加两个新字段 + Builder 默认值改 null → 跑 `FunxyzConfigTest`
2. `FunxyzClient` 加 `resolvedApiKey` + `resolveApiKey()` → 跑现有 `FunxyzClientTest`（应全绿，因为都显式传了 apiKey）
3. 新增 7 个 CDN 行为单测（§5.1）→ 全绿
4. 手测：本机不传 `.apiKey()` 起一个 example，看日志是否打 `funxyz CDN apiKey resolved (length=40)`
