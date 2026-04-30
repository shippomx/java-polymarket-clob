package com.polymarket.clob.gasless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.auth.WalletDerivation;
import com.polymarket.clob.auth.builder.BuilderConfig;
import com.polymarket.clob.auth.builder.BuilderHeaderBuilder;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.ContractRegistry;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;

/**
 * Polymarket Builder-Relayer 客户端（Java，gasless 路径专用）。
 *
 * <p>对齐 npm 包 {@code @polymarket/builder-relayer-client}（v0.0.6）的线上格式与字段顺序。
 * 本类是 SDK 的<b>正式公开 API</b>（前身是 example 包里的 {@code PolymarketRelayer}，已废弃）。</p>
 *
 * <h3>认证</h3>
 * 所有 {@code POST /submit} 走 Builder HMAC（与 CLOB L2 builder attribution 同一套），由
 * {@link BuilderHeaderBuilder} 注入 4 个 {@code POLY_BUILDER_*} header。
 * {@code GET /deployed}、{@code GET /transaction}、{@code GET /nonce} 服务端不验证身份，
 * 这些 GET 不附加任何认证 header。
 *
 * <h3>两种使用模式</h3>
 *
 * <h4>1. 本地私钥模式（example / 单进程脚本）</h4>
 * 一步到位的高层 API：
 * <pre>{@code
 * GaslessRelayer relayer = new GaslessRelayer(url, chainId, builderConfig);
 * String createTxId   = relayer.deploy(localSigner);
 * String approvalTxId = relayer.setupApprovals(localSigner, BigInteger.ZERO);
 * RelayerTxResult r   = relayer.waitForTx(approvalTxId);
 * }</pre>
 *
 * <h4>2. BE-App 协议模式（生产 BE 服务）</h4>
 * BE 不持有私钥，把签名委托给 App 端钱包：
 * <pre>{@code
 * SafeTxPayload payload = relayer.prepareApprovals(eoa, BigInteger.ZERO);
 * // 把 payload.safeTxHash() 32 字节发给 App，按 payload.style() 指示的形态签：
 * //   SAFE_TX_PERSONAL_SIGN → App 用 personal_sign(safeTxHash)
 * //   SAFE_CREATE_DIRECT    → App 用 signTypedData_v4(... CreateProxy)
 * byte[] sigFromApp = ...;                      // 27/28 v 的 raw 65 字节签名
 * String txId = relayer.submit(payload, sigFromApp); // 内部按 style 自动 +4
 * }</pre>
 *
 * <h3>Safe 签名约定</h3>
 * <ol>
 *   <li>计算 EIP-712 摘要（{@link SafeEip712}）；</li>
 *   <li>SafeTx 路径 → {@code personal_sign} 包裹 + {@code v += 4}；
 *       SAFE-CREATE 路径 → 直签，{@code v} 保持 27/28（{@link SafeSignatures}）；</li>
 *   <li>提交前严格按 npm builder-relayer-client v0.0.6 的字段顺序拼 wire JSON。</li>
 * </ol>
 *
 * <h3>批量授权</h3>
 * 使用 Gnosis {@link MultiSend#MULTISEND_CALL_ONLY} 走 DelegateCall，6 笔 approve/setApprovalForAll
 * 打包成一条 SafeTx，单次 nonce、一次 relayer 配额。
 *
 * <p>线程安全（无可变实例字段）。</p>
 */
public final class GaslessRelayer {

    /** 默认 polling 间隔与最大尝试次数；整体最长等待 ~3 分钟。 */
    private static final Duration DEFAULT_POLL = Duration.ofSeconds(2);
    private static final int DEFAULT_MAX_ATTEMPTS = 90;

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final long chainId;
    private final BuilderConfig builderConfig;
    private final BuilderHeaderBuilder headerBuilder;

    /**
     * 构造一个 Builder-Relayer 客户端。
     *
     * @param baseUrl       relayer 根地址，如 {@code https://relayer-v2.polymarket.com}
     * @param chainId       目标链（{@code 137} = Polygon、{@code 80002} = Amoy）
     * @param builderConfig Builder 鉴权配置（local 直持 / remote 转发）
     */
    public GaslessRelayer(String baseUrl,
                          long chainId,
                          BuilderConfig builderConfig) {
        this.baseUrl = trimTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.chainId = chainId;
        this.builderConfig = Objects.requireNonNull(builderConfig, "builderConfig");
        this.headerBuilder = new BuilderHeaderBuilder(builderConfig);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    public BuilderConfig builderConfig() {
        return builderConfig;
    }

    public long chainId() {
        return chainId;
    }

    public String baseUrl() {
        return baseUrl;
    }

    // =========================================================
    //  GET /deployed
    // =========================================================

    /** 查询 Safe 是否已经部署。容忍 {@code true|"true"|{"deployed":true}} 三种返回格式。 */
    public boolean isDeployed(Address safe) throws IOException, InterruptedException {
        Objects.requireNonNull(safe, "safe");
        String url = baseUrl + "/deployed?address=" + safe.toLowerHex();
        HttpResponse<String> resp = sendGet(url);
        ensureOk(resp);
        String body = resp.body() == null ? "" : resp.body().trim();
        if (body.isEmpty()) {
            return false;
        }
        try {
            JsonNode node = mapper.readTree(body);
            if (node.isBoolean()) {
                return node.asBoolean();
            }
            if (node.isTextual()) {
                return "true".equalsIgnoreCase(node.asText());
            }
            JsonNode inner = node.get("deployed");
            if (inner != null && inner.isBoolean()) {
                return inner.asBoolean();
            }
        } catch (Exception ignore) {
            // fall through
        }
        return "true".equalsIgnoreCase(body);
    }

    // =========================================================
    //  prepare* —— 低层 API：摘要计算 + wire 准备（不签名）
    // =========================================================

    /**
     * 准备 SAFE-CREATE 提交材料。返回的 {@link SafeTxPayload} 包含 32 字节
     * {@link SafeTxPayload#safeTxHash()}，需要 EOA 用 {@link SafeTxStyle#SAFE_CREATE_DIRECT}
     * 风格签名，然后通过 {@link #submit} 提交。
     */
    public SafeTxPayload prepareDeploy(Address eoa) {
        Objects.requireNonNull(eoa, "eoa");
        Address safeFactory = ContractRegistry.walletConfig(chainId)
                .orElseThrow(() -> new IllegalStateException("不支持 chainId=" + chainId))
                .safeFactory();
        Address safe = WalletDerivation.deriveSafeWallet(eoa, chainId)
                .orElseThrow(() -> new IllegalStateException("无法派生 Safe 地址"));
        byte[] hash = SafeEip712.createProxyHash(chainId, safeFactory);
        return new SafeTxPayload(
                "SAFE-CREATE",
                eoa,
                safe,
                safeFactory,
                new byte[0],
                0,
                BigInteger.ZERO,
                hash,
                SafeTxStyle.SAFE_CREATE_DIRECT,
                "");
    }

    /**
     * 准备 SAFE.execTransaction 提交材料；&gt;1 笔自动用 MultiSend 打包（DelegateCall）。
     */
    public SafeTxPayload prepareExecute(Address eoa,
                                        List<RelayerTx> txs,
                                        BigInteger nonce,
                                        String description) {
        Objects.requireNonNull(eoa, "eoa");
        Objects.requireNonNull(txs, "txs");
        Objects.requireNonNull(nonce, "nonce");
        if (txs.isEmpty()) {
            throw new IllegalArgumentException("txs 不能为空");
        }
        Address safe = WalletDerivation.deriveSafeWallet(eoa, chainId)
                .orElseThrow(() -> new IllegalStateException("无法派生 Safe 地址"));

        Address to;
        byte[] data;
        int operation;
        if (txs.size() == 1) {
            RelayerTx t = txs.get(0);
            to = t.to();
            data = t.data();
            operation = 0; // Call
        } else {
            data = MultiSend.encode(txs);
            to = MultiSend.MULTISEND_CALL_ONLY;
            operation = 1; // DelegateCall
        }

        byte[] hash = SafeEip712.safeTxHash(chainId, safe, to, data, operation, nonce);
        return new SafeTxPayload(
                "SAFE",
                eoa,
                safe,
                to,
                data,
                operation,
                nonce,
                hash,
                SafeTxStyle.SAFE_TX_PERSONAL_SIGN,
                description == null ? "" : description);
    }

    /** 标准 6 笔授权批次的便捷 prepare（V1 spender 套件）。 */
    public SafeTxPayload prepareApprovals(Address eoa, BigInteger nonce) {
        return prepareExecute(eoa, Calldata.standardApprovalTxs(chainId), nonce,
                "Setup all approvals");
    }

    /**
     * V2 完整授权批次（10 笔）的便捷 prepare：包含 V1 6 笔 + V2 增量 4 笔。
     *
     * <p>用于全新 Safe 在 V2 上线后冷启动；存量 Safe 应使用 {@link #prepareApprovalsV2Only(Address, BigInteger)}
     * 仅追加 V2 spender 的 4 笔，避免重复 approve V1。</p>
     */
    public SafeTxPayload prepareApprovalsV2(Address eoa, BigInteger nonce) {
        return prepareExecute(eoa, Calldata.standardApprovalTxsV2(chainId), nonce,
                "Setup all approvals (V1+V2)");
    }

    /** V2 增量 4 笔的便捷 prepare（V2 spender 授权）。 */
    public SafeTxPayload prepareApprovalsV2Only(Address eoa, BigInteger nonce) {
        return prepareExecute(eoa, Calldata.v2OnlyApprovalTxs(chainId), nonce,
                "Setup V2 approvals only");
    }

    // =========================================================
    //  submit —— 低层 API：调整 v + 拼 wire + POST /submit
    // =========================================================

    /**
     * 提交一笔已经签好名的 SafeTx。
     *
     * @param payload  来自 {@link #prepareDeploy} / {@link #prepareExecute} / {@link #prepareApprovals}
     * @param eoaSig65 65 字节 ECDSA 签名（{@code r || s || v}）；{@code v} 期望 27/28（personal_sign 后或
     *                 直签后）。本方法会按 {@link SafeTxPayload#style()} 自动调整：
     *                 <ul>
     *                   <li>{@link SafeTxStyle#SAFE_TX_PERSONAL_SIGN}：{@code v += 4} → 31/32；</li>
     *                   <li>{@link SafeTxStyle#SAFE_CREATE_DIRECT}：保持 27/28。</li>
     *                 </ul>
     *                 若输入是 yParity 0/1，会先归一到 27/28 再做后续动作。
     * @return relayer 分配的 transactionId；调用方再 {@link #waitForTx(String)} 拿终态
     */
    public String submit(SafeTxPayload payload, byte[] eoaSig65) throws IOException, InterruptedException {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(eoaSig65, "eoaSig65");

        byte[] adjusted = switch (payload.style()) {
            case SAFE_TX_PERSONAL_SIGN -> SafeSignatures.applyExecTransactionVTag(eoaSig65);
            case SAFE_CREATE_DIRECT -> SafeSignatures.normalizeV(eoaSig65);
        };

        ObjectNode req = buildSubmitBody(payload, adjusted, mapper);
        return submitAndExtractTxId(req, payload.wireType() + "/" + payload.description());
    }

    // =========================================================
    //  高层 API（持有 Signer）
    // =========================================================

    /**
     * 部署 Safe（{@code SAFE-CREATE}）。返回 relayer 分配的 transactionId，调用方再
     * {@link #waitForTx(String)} 拿终态。
     */
    public String deploy(Signer signer) throws IOException, InterruptedException {
        Objects.requireNonNull(signer, "signer");
        SafeTxPayload payload = prepareDeploy(signer.address());
        try {
            byte[] sig = SafeSignatures.signEip712Direct(signer, payload.safeTxHash()).join();
            return submit(payload, sig);
        } catch (CompletionException e) {
            unwrapSignerException(e);
            throw e; // unreachable
        }
    }

    /** 通过 Safe execTransaction 执行一组事务；&gt;1 笔自动用 MultiSend 打包。 */
    public String execute(Signer signer,
                          List<RelayerTx> txs,
                          BigInteger nonce,
                          String description) throws IOException, InterruptedException {
        Objects.requireNonNull(signer, "signer");
        SafeTxPayload payload = prepareExecute(signer.address(), txs, nonce, description);
        try {
            byte[] sig = SafeSignatures.personalSignSafeTx(signer, payload.safeTxHash()).join();
            // personalSignSafeTx 已经做了 v+=4，直接进 buildSubmitBody，绕过 submit() 的二次调整。
            ObjectNode req = buildSubmitBody(payload, sig, mapper);
            return submitAndExtractTxId(req, payload.wireType() + "/" + payload.description());
        } catch (CompletionException e) {
            unwrapSignerException(e);
            throw e; // unreachable
        }
    }

    /** 一次性把所有 6 项标准授权打包成 multisend，提交一个 SafeTx，返回 tx_id。 */
    public String setupApprovals(Signer signer, BigInteger nonce)
            throws IOException, InterruptedException {
        return execute(signer, Calldata.standardApprovalTxs(chainId), nonce, "Setup all approvals");
    }

    /**
     * V2 上线（2026-04-28）后的完整授权批次（10 笔）：V1 6 笔 + V2 增量 4 笔。
     * 适用于全新 Safe 冷启动。
     */
    public String setupApprovalsV2(Signer signer, BigInteger nonce)
            throws IOException, InterruptedException {
        return execute(signer, Calldata.standardApprovalTxsV2(chainId), nonce,
                "Setup all approvals (V1+V2)");
    }

    /**
     * 仅 V2 增量 4 笔授权。适用于已经跑过 V1 6 笔的存量 Safe，避免重复 approve V1 spender。
     */
    public String setupApprovalsV2Only(Signer signer, BigInteger nonce)
            throws IOException, InterruptedException {
        return execute(signer, Calldata.v2OnlyApprovalTxs(chainId), nonce,
                "Setup V2 approvals only");
    }

    // =========================================================
    //  GET /transaction?id=...
    // =========================================================

    /** 默认 polling：每 2s 一次，最多 90 次（≈3 min）。 */
    public RelayerTxResult waitForTx(String txId) throws IOException, InterruptedException {
        return waitForTx(txId, DEFAULT_POLL, DEFAULT_MAX_ATTEMPTS);
    }

    public RelayerTxResult waitForTx(String txId, Duration pollInterval, int maxAttempts)
            throws IOException, InterruptedException {
        for (int i = 0; i < maxAttempts; i++) {
            Thread.sleep(pollInterval.toMillis());
            RelayerTxResult r = getTransaction(txId);
            if (r.isTerminal()) {
                if ("FAILED".equals(r.state()) || "INVALID".equals(r.state())) {
                    throw new IOException("Relayer 事务终态=" + r.state()
                            + " txId=" + txId + " hash=" + r.txHash() + " error=" + r.error());
                }
                return r;
            }
        }
        throw new IOException("等待 " + (maxAttempts * pollInterval.toSeconds())
                + "s 后 relayer 事务仍未达终态：" + txId);
    }

    public RelayerTxResult getTransaction(String txId) throws IOException, InterruptedException {
        Objects.requireNonNull(txId, "txId");
        String url = baseUrl + "/transaction?id=" + URLEncoder.encode(txId, StandardCharsets.UTF_8);
        HttpResponse<String> resp = sendGet(url);
        ensureOk(resp);
        return parseTxResult(resp.body());
    }

    /**
     * 从 relayer 读取 Safe 当前 nonce（{@code GET /nonce?address=<eoa>&type=SAFE}）。
     *
     * <p><b>注意</b>：这一接口可能 stale；首次部署后立即用，期望返回 0/1。重要场景请用链上
     * {@code Safe.nonce()} 读。</p>
     */
    public BigInteger getRelayerNonce(Address eoa) throws IOException, InterruptedException {
        Objects.requireNonNull(eoa, "eoa");
        String url = baseUrl + "/nonce?address=" + eoa.toLowerHex() + "&type=SAFE";
        HttpResponse<String> resp = sendGet(url);
        ensureOk(resp);
        String body = resp.body() == null ? "" : resp.body().trim();
        if (body.isEmpty()) {
            return BigInteger.ZERO;
        }
        try {
            JsonNode node = mapper.readTree(body);
            if (node.isNumber()) {
                return node.bigIntegerValue();
            }
            if (node.isTextual()) {
                return new BigInteger(node.asText());
            }
            // 实测 polymarket relayer-v2 返回 {"nonce": N} 形态；额外兼容 {"data":{"nonce":N}} / {"result":...}
            // 以及一些常见别名（safeNonce / value），任意一个匹配即可。
            if (node.isObject()) {
                JsonNode inner = node;
                for (String wrap : new String[]{"data", "result", "transaction"}) {
                    JsonNode w = inner.get(wrap);
                    if (w != null && w.isObject()) {
                        inner = w;
                        break;
                    }
                }
                for (String key : new String[]{"nonce", "safeNonce", "value"}) {
                    JsonNode v = inner.get(key);
                    if (v == null || v.isNull()) {
                        continue;
                    }
                    if (v.isNumber()) {
                        return v.bigIntegerValue();
                    }
                    if (v.isTextual() && !v.asText().isBlank()) {
                        return new BigInteger(v.asText().trim());
                    }
                }
                throw new IOException("relayer /nonce 返回对象但缺少 nonce 字段：" + body);
            }
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception ignore) {
            // 落到下面的兜底
        }
        return new BigInteger(body);
    }

    // =========================================================
    //  内部工具
    // =========================================================

    /**
     * 严格按 npm builder-relayer-client v0.0.6 字段顺序拼 wire JSON。
     *
     * <p>package-private static：本方法是纯函数（无副作用、无 IO、无 SDK 状态），独立暴露给同包
     * 单元测试做字节级 wire 字段稳定性回归。</p>
     */
    static ObjectNode buildSubmitBody(SafeTxPayload payload, byte[] sig65, ObjectMapper mapper) {
        ObjectNode req = mapper.createObjectNode();
        req.put("type", payload.wireType());
        req.put("from", payload.eoa().toLowerHex());
        req.put("to", payload.to().toLowerHex());
        req.put("proxyWallet", payload.safe().toLowerHex());
        req.put("data", "0x" + HexFormat.of().formatHex(payload.data()));
        req.put("signature", "0x" + HexFormat.of().formatHex(sig65));

        if (payload.isSafeCreate()) {
            ObjectNode params = req.putObject("signatureParams");
            params.put("paymentToken", Address.ZERO.toLowerHex());
            params.put("payment", "0");
            params.put("paymentReceiver", Address.ZERO.toLowerHex());
        } else {
            req.put("nonce", payload.nonce().toString());
            ObjectNode params = req.putObject("signatureParams");
            params.put("gasPrice", "0");
            params.put("operation", Integer.toString(payload.operation()));
            params.put("safeTxnGas", "0"); // 注意：拼写 "Txn"，与 Rust/上游字段名保持一致
            params.put("baseGas", "0");
            params.put("gasToken", Address.ZERO.toLowerHex());
            params.put("refundReceiver", Address.ZERO.toLowerHex());
            req.put("metadata", payload.description());
        }
        return req;
    }

    private HttpResponse<String> sendGet(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private String submitAndExtractTxId(ObjectNode requestBody, String tag)
            throws IOException, InterruptedException {
        String json = mapper.writeValueAsString(requestBody);
        long timestamp = Instant.now().getEpochSecond();
        Map<String, String> builderHeaders;
        try {
            builderHeaders = headerBuilder.build("POST", "/submit", json, timestamp).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IOException("relayer /submit [" + tag + "] Builder HMAC 头生成失败：" + cause, cause);
        }
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(baseUrl + "/submit"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        builderHeaders.forEach(reqBuilder::header);
        HttpResponse<String> resp = http.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("relayer /submit [" + tag + "] 失败 status=" + resp.statusCode()
                    + " body=" + resp.body());
        }
        RelayerTxResult r = parseTxResult(resp.body());
        if (r.txId() == null || r.txId().isBlank()) {
            throw new IOException("relayer /submit [" + tag + "] 未返回 transactionId：" + resp.body());
        }
        return r.txId();
    }

    private RelayerTxResult parseTxResult(String body) throws IOException {
        if (body == null || body.isBlank()) {
            throw new IOException("relayer 返回空 body");
        }
        JsonNode node = mapper.readTree(body);
        if (node.isArray() && node.size() > 0) {
            node = node.get(0);
        }
        for (String key : new String[]{"data", "result", "transaction"}) {
            JsonNode wrapped = node.get(key);
            if (wrapped != null && wrapped.isObject()) {
                node = wrapped;
                break;
            }
        }

        String txId = textOrNull(node, "transactionID", "transactionId");
        String state = textOrNull(node, "state");
        String hash = textOrNull(node, "transactionHash", "hash");
        String error = textOrNull(node, "errorMsg", "error", "reason", "failureReason", "revertReason", "message");
        if (state != null && state.startsWith("STATE_")) {
            state = state.substring("STATE_".length());
        }
        if (state != null) {
            state = state.toUpperCase();
        }
        return new RelayerTxResult(txId, state, hash, error);
    }

    private static String textOrNull(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull()) {
                String s = v.isTextual() ? v.asText() : v.toString();
                if (!s.isBlank() && !"\"\"".equals(s) && !"null".equals(s)) {
                    return s;
                }
            }
        }
        return null;
    }

    private static void ensureOk(HttpResponse<String> resp) throws IOException {
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("relayer HTTP " + resp.statusCode() + " body=" + resp.body());
        }
    }

    private static void unwrapSignerException(CompletionException e) throws IOException {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        if (cause instanceof IOException io) {
            throw io;
        }
        if (cause instanceof RuntimeException re) {
            throw re;
        }
        throw new IOException("签名失败：" + cause, cause);
    }

    private static String trimTrailingSlash(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
