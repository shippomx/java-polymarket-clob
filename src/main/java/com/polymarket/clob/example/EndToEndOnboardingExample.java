package com.polymarket.clob.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.polymarket.clob.AuthenticatedClobClient;
import com.polymarket.clob.BuilderClobClient;
import com.polymarket.clob.ClobClient;
import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.auth.WalletDerivation;
import com.polymarket.clob.auth.builder.BuilderConfig;
import com.polymarket.clob.exception.ClobApiException;
import com.polymarket.clob.gasless.GaslessRelayer;
import com.polymarket.clob.gasless.RelayerTxResult;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.AssetType;
import com.polymarket.clob.model.BalanceAllowanceRequest;
import com.polymarket.clob.order.CreateOrderOptions;
import com.polymarket.clob.order.LimitOrderArgsV2;
import com.polymarket.clob.order.OrderType;
import com.polymarket.clob.order.PostOrderResponse;
import com.polymarket.clob.order.SignedOrderV2;
import com.polymarket.clob.order.TickSize;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Polymarket 冷启动端到端示例（<b>仅 gasless 路线</b>）。
 *
 * <p>从一个 EOA 私钥起步，全程不消耗 EOA 上的 MATIC：
 * <ol>
 *   <li><b>Phase 0（CLOB L2 + Builder Key 自举）</b> ——
 *       EOA 通过 EIP-712 调 {@code POST /auth/api-key}（create 失败回落 derive）拿到一份
 *       L2 凭证 {@code creds}；再用 {@code creds} 调 {@code POST /auth/builder-api-key}
 *       生成一份 Builder 凭证 {@code builderCreds}。后者是 relayer 的鉴权材料。</li>
 *   <li><b>Phase 1（gasless 链上）</b> ——
 *       用 {@code BuilderConfig.local(builderCreds)} 构造 {@link GaslessRelayer}，
 *       所有 {@code POST /submit} 自动带上
 *       {@code POLY_BUILDER_API_KEY/PASSPHRASE/TIMESTAMP/SIGNATURE} 四联头：
 *     <ul>
 *       <li>{@code GET /deployed} 检测 Safe 是否已部署；</li>
 *       <li>未部署 → {@code POST /submit} type=SAFE-CREATE → poll {@code GET /transaction}；</li>
 *       <li>新部署后立刻 {@code POST /submit} type=SAFE，把 6 笔 approve/setApprovalForAll 通过 MultiSend
 *           打包成一条 SafeTx（nonce=0）→ poll；</li>
 *       <li>已部署且 {@code forceApprove=true} 时也执行授权批次；否则跳过。</li>
 *     </ul>
 *   </li>
 *   <li><b>Phase 1.5（多链充值地址）</b> ——
 *       调 {@link PolymarketBridge}（{@code https://bridge.polymarket.com}）
 *       {@code POST /deposit} 申请一组与当前 Safe 一对一绑定的 EVM/SVM/BTC 充值地址；用户从任意支持链
 *       打来的资产会被后端中转商自动桥到 Polygon USDC.e 进 Safe。无需鉴权；地址可缓存。</li>
 *   <li><b>Phase 2（CLOB 已认证客户端）</b> ——
 *       用 {@code creds} 重新挂一份 {@link AuthenticatedClobClient}，再
 *       {@code promoteToBuilder(builderCreds)} 升级成 {@link BuilderClobClient}（订单自动带
 *       builder tag）；funder = 第 1 步部署的 Safe；
 *       {@code GET /balance-allowance} 健全性检查。</li>
 *   <li><b>Phase 3（试挂单）</b> —— 读 tick/neg-risk → 构造 {@link LimitOrderArgs} → CTF Exchange EIP-712 签名；
 *       {@code submit=true} 时真挂单 + 立即撤单。</li>
 * </ol>
 *
 * <h3>必填环境变量</h3>
 * <ul>
 *   <li>（无）私钥/tokenId 直接写在源码顶部，便于 IDE 一键跑。生产请改成读 env / vault。</li>
 * </ul>
 *
 * <h3>可选环境变量</h3>
 * <ul>
 *   <li>{@code RELAYER_URL}（默认 {@code https://relayer-v2.polymarket.com}）；</li>
 *   <li>{@code CLOB_ENDPOINT}（默认 {@code https://clob.polymarket.com}）；</li>
 *   <li>{@code ORDER_PRICE / ORDER_SIZE / ORDER_SIDE}：试挂单参数，默认 0.1 / 5 / BUY；</li>
 *   <li>{@code POLY_BUILDER_KEY} / {@code POLY_BUILDER_SECRET} / {@code POLY_BUILDER_PASSPHRASE}：
 *       三件套同时设置时，跳过 {@code POST /auth/builder-api-key}，直接复用注入的 Builder Key。
 *       优先级最高（覆盖 builder-override 文件与本地缓存）。</li>
 *   <li>{@code POLY_BUILDER_FILE}：Builder 凭证 override 文件路径。默认
 *       {@code ~/.polymarket-clob/builder-override.json}。文件格式（{@code builderCode} 为
 *       2026-04-28 V2 上线后新增的可选字段，对应 polymarket.com/settings 复制的 32 字节 hex）：
 *       <pre>{@code
 *       {
 *         "apiKey":     "...",
 *         "secret":     "...",
 *         "passphrase": "...",
 *         "builderCode": "0x0000000000000000000000000000000000000000000000000000000000000001"
 *       }
 *       }</pre>
 *       三件套（{@code apiKey/secret/passphrase}）齐全时被视为「手填的长期 Builder Key」，覆盖缓存中的
 *       Builder 部分；L2 凭证仍按缓存/derive 走。{@code builderCode} 字段独立解析（缺失或空字符串
 *       → 视为不归属，订单 {@code builder} 字段填全 0）。
 *       适用于：(a) 命中后端「单账户 Builder Key 数量上限」导致 403；
 *       (b) 持有从 polymarket.com/settings 申领的稳定 Builder Key 想跨机器复用；
 *       (c) 想给挂出的订单打 builder code 让交易量计入自己的构建者账户。</li>
 *   <li>{@code POLY_BUILDER_CODE}：32 字节 hex（{@code 0x} + 64 hex chars，共 66 字符）。
 *       优先级高于 {@code builder-override.json} 中的 {@code builderCode} 字段；都没设时
 *       订单 {@code builder} 字段填全 0（不归属）。仅影响 V2 订单签名 struct，与 L2 HMAC 无关。</li>
 *   <li>{@code POLY_CREDS_FILE}：本地凭证缓存文件路径。默认
 *       {@code ~/.polymarket-clob/creds-<chainId>-<eoa-lowercase>.json}。
 *       该文件存在时<b>同时复用</b> L2 与 Builder 凭证，跳过 {@code POST /auth/api-key} 与
 *       {@code POST /auth/builder-api-key} 两次申请；不存在时正常申请并在成功后写盘
 *       （POSIX 系统会自动 chmod 600）。强制重新申请：删掉文件即可。</li>
 * </ul>
 *
 * <h3>运行</h3>
 * <pre>{@code
 * mvn -B compile exec:java \
 *     -Dexec.mainClass=com.polymarket.clob.example.EndToEndOnboardingExample
 * }</pre>
 *
 * <p><b>关于 EOA 自付 gas 路径</b>：见 {@link SafeWalletExample}（保留作为不依赖 relayer 的备用参考）。
 */
public final class EndToEndOnboardingExample {

    /** 缓存文件 schema 版本——日后字段扩展时用这个判断是否兼容旧文件。 */
    private static final int CREDS_FILE_SCHEMA_VERSION = 1;

    private static final ObjectMapper CREDS_MAPPER = new ObjectMapper();

    private EndToEndOnboardingExample() {}

    /** L2 + Builder 两份凭证的容器，用作本地缓存的内存对应物。 */
    private record CachedCreds(ApiCredentials l2, ApiCredentials builder) {}

    /**
     * 解析「外部注入的 Builder 凭证」。优先级：
     * <ol>
     *   <li>{@code POLY_BUILDER_KEY/SECRET/PASSPHRASE} 三件套 env（最高，CI/调试场景临时覆盖）；</li>
     *   <li>{@code POLY_BUILDER_FILE} / 默认 {@code ~/.polymarket-clob/builder-override.json} 文件；</li>
     * </ol>
     * 全都没有则返回 empty，由调用方走 createBuilderApiKey。
     * 这层间接是为了让单账户超出 Builder Key 上限（POST /auth/builder-api-key 返 403）时仍能跑完，
     * 且把 secret 从 env 移到文件可避免 shell 历史/进程列表里泄漏。
     */
    private static Optional<ApiCredentials> resolveBuilderCredsOverride() {
        Optional<ApiCredentials> fromEnv = readBuilderCredsFromEnv();
        if (fromEnv.isPresent()) {
            System.out.println("（已从 POLY_BUILDER_* 环境变量注入 Builder 凭证）");
            return fromEnv;
        }
        return readBuilderCredsFromFile();
    }

    /** 三件套都设置时返回注入的 Builder 凭证；任一空返回 empty。仅由 {@link #resolveBuilderCredsOverride} 调用。 */
    private static Optional<ApiCredentials> readBuilderCredsFromEnv() {
        String key = System.getenv("POLY_BUILDER_KEY");
        String secret = System.getenv("POLY_BUILDER_SECRET");
        String pass = System.getenv("POLY_BUILDER_PASSPHRASE");
        if (key == null || key.isBlank()
                || secret == null || secret.isBlank()
                || pass == null || pass.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ApiCredentials(key, secret, pass));
    }

    /**
     * 解析 {@code POLY_BUILDER_FILE} 或退回默认路径 {@code ~/.polymarket-clob/builder-override.json}。
     * 文件不存在 / 字段不全 / 解析失败均视作未注入，回退到正常申请流程。
     */
    private static Optional<ApiCredentials> readBuilderCredsFromFile() {
        String override = System.getenv("POLY_BUILDER_FILE");
        Path file;
        if (override != null && !override.isBlank()) {
            file = Path.of(override);
        } else {
            String home = System.getProperty("user.home", ".");
            file = Path.of(home, ".polymarket-clob", "builder-override.json");
        }
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonNode root = CREDS_MAPPER.readTree(file.toFile());
            ApiCredentials c = parseCredsNode(root);
            if (c == null) {
                System.err.println("⚠ Builder 凭证文件字段不全（需 apiKey/secret/passphrase 三项），跳过：" + file);
                return Optional.empty();
            }
            System.out.println("（已从 Builder 凭证文件注入：" + file + "）");
            return Optional.of(c);
        } catch (IOException e) {
            System.err.println("⚠ 读取 Builder 凭证文件失败（将忽略）：" + file + " " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 解析「外部注入的 builder code（bytes32）」。优先级：
     * <ol>
     *   <li>{@code POLY_BUILDER_CODE} 环境变量；</li>
     *   <li>{@code POLY_BUILDER_FILE} / 默认 {@code ~/.polymarket-clob/builder-override.json}
     *       中的顶层 {@code builderCode} 字段；</li>
     * </ol>
     * 都没有则返回 {@link LimitOrderArgsV2#BYTES32_ZERO}，即订单不归属任何 builder。
     *
     * <p>注意：builder code 与 {@link #resolveBuilderCredsOverride() Builder API Key} 是两条
     * 独立链路—— code 进 EIP-712 签名 struct 决定链上归属，Key 走 HMAC 头决定 L2 鉴权。两者各自缺失
     * 互不影响。</p>
     */
    private static String resolveBuilderCode() {
        String fromEnv = System.getenv("POLY_BUILDER_CODE");
        if (fromEnv != null && !fromEnv.isBlank()) {
            String code = normalizeBuilderCode(fromEnv.trim(), "POLY_BUILDER_CODE");
            System.out.println("（已从 POLY_BUILDER_CODE 环境变量注入 builder code）");
            return code;
        }
        return readBuilderCodeFromFile().orElse(LimitOrderArgsV2.BYTES32_ZERO);
    }

    /**
     * 从 {@code builder-override.json} 顶层读取 {@code builderCode} 字段。文件不存在 / 字段缺失
     * 视作未注入，回退到全 0；字段存在但格式非法（非 0x-prefixed 32 字节 hex）会直接抛错，
     * 避免静默挂出归属失败的订单。
     */
    private static Optional<String> readBuilderCodeFromFile() {
        String override = System.getenv("POLY_BUILDER_FILE");
        Path file;
        if (override != null && !override.isBlank()) {
            file = Path.of(override);
        } else {
            String home = System.getProperty("user.home", ".");
            file = Path.of(home, ".polymarket-clob", "builder-override.json");
        }
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonNode root = CREDS_MAPPER.readTree(file.toFile());
            String raw = root.path("builderCode").asText(null);
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            String code = normalizeBuilderCode(raw.trim(), file.toString());
            System.out.println("（已从 " + file + " 注入 builder code）");
            return Optional.of(code);
        } catch (IOException e) {
            System.err.println("⚠ 读取 builderCode 字段失败（将用全 0）：" + file + " " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 把 builder code 归一化为 {@code OrderBuilder.validateBytes32} 接受的格式：小写 {@code 0x} 前缀 +
     * 64 个 hex 字符，总长 66。任何长度/字符违例直接抛 {@link IllegalArgumentException}。
     */
    private static String normalizeBuilderCode(String raw, String source) {
        String s = raw;
        if (s.startsWith("0X")) {
            s = "0x" + s.substring(2);
        }
        if (!s.startsWith("0x") || s.length() != 66) {
            throw new IllegalArgumentException(source
                    + " 中的 builderCode 必须是 0x-prefixed 32-byte hex（共 66 字符），实际：" + raw);
        }
        for (int i = 2; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                throw new IllegalArgumentException(source
                        + " 中的 builderCode 含非 hex 字符 '" + c + "'，原值：" + raw);
            }
        }
        return s;
    }

    /**
     * 解析 {@code POLY_CREDS_FILE} 或退回到默认路径。文件名按 chainId+EOA 维度隔离，
     * 防止切换账户时误读到别人的凭证。
     */
    private static Path resolveCredsFile(long chainId, Address eoa) {
        String override = System.getenv("POLY_CREDS_FILE");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String home = System.getProperty("user.home", ".");
        String fname = "creds-" + chainId + "-" + eoa.toLowerHex() + ".json";
        return Path.of(home, ".polymarket-clob", fname);
    }

    /**
     * 读缓存：文件存在且 schema 完整时返回 {@link CachedCreds}；任何 IO/解析问题都视为
     * 「缓存不可用」并返回 empty —— 调用方会回退到正常申请流程。
     */
    private static Optional<CachedCreds> loadCachedCreds(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonNode root = CREDS_MAPPER.readTree(file.toFile());
            JsonNode l2Node = root.path("l2");
            JsonNode builderNode = root.path("builder");
            if (!l2Node.isObject() || !builderNode.isObject()) {
                return Optional.empty();
            }
            ApiCredentials l2 = parseCredsNode(l2Node);
            ApiCredentials builder = parseCredsNode(builderNode);
            if (l2 == null || builder == null) {
                return Optional.empty();
            }
            return Optional.of(new CachedCreds(l2, builder));
        } catch (IOException e) {
            System.err.println("⚠ 读取本地凭证缓存失败（将重新申请）：" + file + " " + e.getMessage());
            return Optional.empty();
        }
    }

    private static ApiCredentials parseCredsNode(JsonNode node) {
        String apiKey = node.path("apiKey").asText(null);
        String secret = node.path("secret").asText(null);
        String pass = node.path("passphrase").asText(null);
        if (apiKey == null || apiKey.isBlank()
                || secret == null || secret.isBlank()
                || pass == null || pass.isBlank()) {
            return null;
        }
        return new ApiCredentials(apiKey, secret, pass);
    }

    /**
     * 原子写盘：先写到 sibling {@code *.tmp} 再 ATOMIC_MOVE，避免崩溃留半截文件。
     * POSIX 系统额外 chmod 600，把 secret 限定为仅当前用户可读。
     */
    private static void saveCachedCreds(Path file,
                                        long chainId,
                                        Address eoa,
                                        ApiCredentials l2,
                                        ApiCredentials builder) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ObjectNode root = CREDS_MAPPER.createObjectNode();
            root.put("schema", CREDS_FILE_SCHEMA_VERSION);
            root.put("eoa", eoa.toLowerHex());
            root.put("chainId", chainId);
            root.put("createdAt", Instant.now().toString());
            root.set("l2", credsToNode(l2));
            root.set("builder", credsToNode(builder));

            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            CREDS_MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), root);
            applyOwnerOnlyPermsBestEffort(tmp);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFail) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            applyOwnerOnlyPermsBestEffort(file);
        } catch (IOException e) {
            System.err.println("⚠ 写入本地凭证缓存失败（不影响本次运行）：" + file + " " + e.getMessage());
        }
    }

    private static ObjectNode credsToNode(ApiCredentials c) {
        ObjectNode n = CREDS_MAPPER.createObjectNode();
        n.put("apiKey", c.apiKey());
        n.put("secret", c.secret());
        n.put("passphrase", c.passphrase());
        return n;
    }

    /** chmod 600 等价物；非 POSIX 文件系统（如 Windows）静默跳过。 */
    private static void applyOwnerOnlyPermsBestEffort(Path file) {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, perms);
        } catch (IOException ignored) {
            // 权限设不上不致命，比如挂载点禁止 chmod；继续运行。
        }
    }

    public static void main(String[] args) throws Exception {
        // ---------- 必填 ----------
        // 仅作 demo：把私钥/token 写在源码里，IDE 一键就能跑。生产请改成 env / vault。
        String privateKey = "0x246c06019672b34b8b1cff15c71060de5d0e8d5d2ffba9d5e5ce8c0647ee3b68";
        String tokenIdStr = "8501497159083948713316135768103773293754490207922884688769443031624417212426";
        // 这把私钥派生的 EOA：0x0Ba73Fe06B2c537eEEe21690362cF5222d9E5D22
        // Polygon (137) 上对应的 Safe (funder)：0x82f55b4bD815FeAEc6E92469c7788Da4E9685D0A

        // ---------- 可选环境 ----------
        String relayerUrl = Optional.ofNullable(System.getenv("RELAYER_URL"))
                .orElse("https://relayer-v2.polymarket.com");
        String clobEndpoint = Optional.ofNullable(System.getenv("CLOB_ENDPOINT"))
                .orElse("https://clob.polymarket.com");
        long chainId = 137;
        // 仅在 Safe 已部署但需要补刷授权（如新增 V2 spender）时设 true；
        // 默认 false：未部署的新 EOA 自动跑「部署+授权」一条龙；已部署且授权过的账户直接进 Phase 3。
        boolean forceApprove = false;
        boolean submit = true;

        BigInteger tokenId = new BigInteger(tokenIdStr);
        BigDecimal price = new BigDecimal(Optional.ofNullable(System.getenv("ORDER_PRICE")).orElse("0.1"));
        BigDecimal size = new BigDecimal(Optional.ofNullable(System.getenv("ORDER_SIZE")).orElse("5"));
        Side side = Side.valueOf(Optional.ofNullable(System.getenv("ORDER_SIDE")).orElse("BUY").toUpperCase());

        // ---------- 准备 signer ----------
        LocalSigner signer = LocalSigner.fromPrivateKey(privateKey);
        Address eoa = signer.address();
        Address safe = WalletDerivation.deriveSafeWallet(eoa, chainId)
                .orElseThrow(() -> new IllegalStateException("无法派生 Safe，chainId=" + chainId));

        System.out.println("================ 冷启动 (gasless) ================");
        System.out.println("EOA           = " + eoa.toHex());
        System.out.println("Safe (派生)   = " + safe.toHex());
        System.out.println("Relayer       = " + relayerUrl);
        System.out.println("CLOB          = " + clobEndpoint);
        System.out.println("Chain ID      = " + chainId);

        try (ClobClient base = ClobClient.builder()
                .endpoint(clobEndpoint)
                .chainId(chainId)
                .build()) {

            // ============================================================
            // Phase 0：CLOB L2 + Builder Key 自举
            //   relayer /submit 需要 POLY_BUILDER_* HMAC，先把这份凭证准备好。
            //   注意顺序：L2 derive 与 createBuilderApiKey 都不需要 Safe 已部署，
            //   只需要 EOA 能签 EIP-712 即可——所以可以放在链上动作之前。
            //
            //   优先级：本地缓存文件 > POLY_BUILDER_* env (仅覆盖 Builder) > 现场申请。
            //   缓存命中直接跳过两次网络申请；现场申请成功后会写回缓存供下次复用。
            // ============================================================
            System.out.println("\n--- Phase 0：CLOB L2 + Builder Key 自举 ---");

            Path credsFile = resolveCredsFile(chainId, eoa);
            Optional<CachedCreds> cached = loadCachedCreds(credsFile);

            ApiCredentials creds;
            ApiCredentials builderCreds;
            boolean fromCache = cached.isPresent();

            if (fromCache) {
                System.out.println("（命中本地凭证缓存：" + credsFile + "）");
                creds = cached.get().l2();
                // env / override 文件仍可强制覆盖 Builder cred —— 用于切换不同的 Builder 标识或 settings 页面长期 key
                builderCreds = resolveBuilderCredsOverride().orElse(cached.get().builder());
                if (!builderCreds.equals(cached.get().builder())) {
                    System.out.println("（外部 Builder 凭证已覆盖缓存中的 Builder 部分）");
                }
                // 缓存命中时跳过任何 /auth/* 端点；后续 Phase 2 会用 creds 直接挂 AuthenticatedClobClient。
            } else {
                // 0a) 隐式 derive 一份 L2 凭证（POLY_GNOSIS_SAFE 路径，funder 自动派生为 Safe）
                AuthenticatedClobClient bootstrap = base
                        .authenticate(signer, SignatureType.POLY_GNOSIS_SAFE, BigInteger.ZERO)
                        .join();

                // 0b) 显式再调一次 createOrDerive —— 同一私钥每次返回的三段都一样（确定性）
                long now0 = Instant.now().getEpochSecond();
                creds = bootstrap.auth()
                        .createOrDeriveApiKey(signer, chainId, now0, BigInteger.ZERO)
                        .join();

                // 0c) 用 L2 creds 调 /auth/builder-api-key 生成一份 Builder 凭证。
                //     注意：这个端点每次都会真生成一份「全新」的 Builder Key（不像 L2 derive 是确定性的），
                //     所以反复跑此 example 会在后台积累一堆 Builder Key——后端会对单账户的 Builder Key 数
                //     设上限/风控，超过后再调本端点会直接返 403。
                //     生产里建议改成「persist 之后复用」或从 polymarket.com/settings 申领长期 key
                //     后通过 POLY_BUILDER_* 三件套或 builder-override.json 注入 —— 注入则跳过本步。
                builderCreds = resolveBuilderCredsOverride()
                        .orElseGet(() -> {
                            long now1 = Instant.now().getEpochSecond();
                            try {
                                return bootstrap.builder()
                                        .createBuilderApiKey(eoa, creds, now1)
                                        .join();
                            } catch (java.util.concurrent.CompletionException ce) {
                                Throwable cause = ce.getCause() == null ? ce : ce.getCause();
                                if (cause instanceof ClobApiException api) {
                                    System.err.println("POST /auth/builder-api-key 失败 status="
                                            + api.getStatusCode() + " body=" + api.getBody());
                                    if (api.getStatusCode() == 403) {
                                        System.err.println("  ↑ 403 通常意味着 EOA 已达单账户 Builder Key 上限。");
                                        System.err.println("    解决（任选其一）：");
                                        System.err.println("      a) 把任何一组之前申请到的 Builder cred 写到");
                                        System.err.println("         ~/.polymarket-clob/builder-override.json，格式：");
                                        System.err.println("         {\"apiKey\":\"...\",\"secret\":\"...\",\"passphrase\":\"...\"}");
                                        System.err.println("      b) 或导出 POLY_BUILDER_KEY / POLY_BUILDER_SECRET / POLY_BUILDER_PASSPHRASE 再跑。");
                                    }
                                }
                                throw ce;
                            }
                        });

                // 写回缓存，下次启动直接命中
                saveCachedCreds(credsFile, chainId, eoa, creds, builderCreds);
                System.out.println("（已写入本地凭证缓存：" + credsFile + "）");
            }

            System.out.println("---- L2 API credentials (派生自 EOA " + eoa.toHex() + ") ----");
            System.out.println("  apiKey     = " + creds.apiKey());
            System.out.println("  secret     = " + creds.secret());
            System.out.println("  passphrase = " + creds.passphrase());
            System.out.println("---- Builder API credentials (caller=" + eoa.toHex() + ") ----");
            System.out.println("  apiKey     = " + builderCreds.apiKey());
            System.out.println("  secret     = " + builderCreds.secret());
            System.out.println("  passphrase = " + builderCreds.passphrase());
            System.out.println("  → 立刻用作：(a) relayer /submit 的 POLY_BUILDER_* HMAC 头；");
            System.out.println("              (b) 后续 BuilderClobClient 给挂出去的订单打 builder tag。");

            BuilderConfig builderConfig = BuilderConfig.local(builderCreds);

            // ============================================================
            // Phase 1：gasless 链上 —— 部署 Safe + 标准授权批次
            // ============================================================
            GaslessRelayer relayer = new GaslessRelayer(relayerUrl, chainId, builderConfig);

            boolean deployed = relayer.isDeployed(safe);
            System.out.println("\n--- Phase 1：链上（gasless via relayer）---");
            System.out.println("Safe deployed? = " + deployed);

            // justDeployed = 本次运行内是否真的执行过 SAFE-CREATE。默认必须是 false，
            // 否则 Safe 已部署的二次运行会错误地走 nonce=0 分支，导致 SafeTx 链上 revert。
            boolean justDeployed = false;
            if (!deployed) {
                System.out.println("→ POST /submit type=SAFE-CREATE ...");
                String createTxId = relayer.deploy(signer);
                System.out.println("  txId = " + createTxId + "（轮询中）");
                RelayerTxResult r = relayer.waitForTx(createTxId);
                System.out.println("  state=" + r.state() + "  hash=" + r.txHash());
                justDeployed = true;
            }

            // 刚部署的 Safe **必须**跑授权批次（链上零授权，不挂单）；已部署的 Safe 仅在
            // forceApprove=true 时重刷（覆盖新增 V2 spender 等场景）。
            boolean shouldApprove = justDeployed || forceApprove;
            if (shouldApprove) {
                // 刚部署 → Safe 链上 nonce 一定 = 0，跳过一次 GET /nonce。
                // 已部署 → 必须问 relayer 当前 nonce，否则会因为 nonce 不一致 revert。
                BigInteger nonce = justDeployed
                        ? BigInteger.ZERO
                        : relayer.getRelayerNonce(eoa);
                // V2 上线（2026-04-28）后必须用 setupApprovalsV2，10 笔批次同时覆盖 V1+V2 spender。
                System.out.println("\n→ POST /submit type=SAFE （MultiSend 10 笔授权 V1+V2，nonce=" + nonce + "）...");
                String approveTxId = relayer.setupApprovalsV2(signer, nonce);
                System.out.println("  txId = " + approveTxId + "（轮询中）");
                RelayerTxResult r = relayer.waitForTx(approveTxId);
                System.out.println("  state=" + r.state() + "  hash=" + r.txHash());
            } else {
                System.out.println("→ Safe 已部署，跳过授权批次。设 forceApprove=true 可强制重发。");
            }

            // ============================================================
            // Phase 1.5：申请多链充值地址（独立 host：bridge.polymarket.com，无需鉴权）
            //   这一步**只读**，不上链、不签名；多次调用通常会返回同一组地址。
            // ============================================================
            System.out.println("\n--- Phase 1.5：申请充值地址 ---");
            try {
                PolymarketBridge bridge = new PolymarketBridge();
                PolymarketBridge.DepositResponse dep = bridge.createDepositAddresses(safe);
                System.out.println("绑定 Safe = " + safe.toHex());
                dep.formatLines().forEach(line -> System.out.println("  " + line));
                if (dep.note() != null && !dep.note().isBlank()) {
                    System.out.println("  note = " + dep.note());
                }
                System.out.println("  → 用户向上面任一地址打 USDC（含 Ethereum L1 USDC、Solana USDC 等），");
                System.out.println("    Polymarket 后端中转商会自动桥成 Polygon USDC.e 入金到 Safe。");
            } catch (Exception e) {
                // bridge 是只读的辅助步骤；失败不影响后续下单流程，只警告即可。
                System.err.println("⚠ 申请充值地址失败（不影响下单流程）：" + e.getMessage());
            }

            // ============================================================
            // Phase 2：CLOB —— 用「显式」creds 重挂客户端，并升级到 Builder 模式
            // ============================================================
            System.out.println("\n--- Phase 2：CLOB 认证（funder=Safe）---");
            AuthenticatedClobClient authed = base.authenticate(
                    signer, SignatureType.POLY_GNOSIS_SAFE, creds);
            BuilderClobClient clob = authed.promoteToBuilder(builderConfig);

            if (!clob.funder().equals(safe)) {
                throw new IllegalStateException("CLOB funder=" + clob.funder().toHex()
                        + " 与 Safe=" + safe.toHex() + " 不一致");
            }
            System.out.println("funder(Safe) = " + clob.funder().toHex());
            System.out.println("closedOnly   = " + clob.closedOnlyMode().join().closedOnly());

            var ba = clob.balanceAllowance(BalanceAllowanceRequest.builder()
                    .assetType(AssetType.COLLATERAL).build()).join();
            System.out.println("USDC balance = " + ba.balance());
            ba.allowances().forEach((operator, amount) ->
                    System.out.println("  allowance " + operator.toHex() + " = " + amount));

            // ============================================================
            // Phase 3：试挂限价单（V2 路径，builder-attributed）
            // ============================================================
            System.out.println("\n--- Phase 3：试挂 V2 限价单 ---");
            TickSize tick = clob.market().getTickSize(tokenIdStr).join();
            boolean negRisk = clob.market().getNegRisk(tokenIdStr).join();
            // V2 EIP-712 签名 struct 中已移除 feeRateBps（V2 改用 builder code 走费率归属），
            // 这里不再读取/注入 feeRateBps。
            CreateOrderOptions opts = CreateOrderOptions.of(tick, negRisk);
            System.out.printf("token=%s  tick=%s  negRisk=%s%n",
                    tokenIdStr, tick.wireValue(), negRisk);

            // builder code（bytes32）是订单链上归属字段，与上面 BuilderConfig（HMAC Key）独立。
            // 优先级：POLY_BUILDER_CODE env > builder-override.json#builderCode > 全 0（不归属）。
            String builderCode = resolveBuilderCode();
            if (LimitOrderArgsV2.BYTES32_ZERO.equals(builderCode)) {
                System.out.println("builder code = (none) → 订单不会归属到任何 builder");
                System.out.println("  填入步骤：在 ~/.polymarket-clob/builder-override.json 顶层加");
                System.out.println("           \"builderCode\": \"0x...\"（polymarket.com/settings 复制），");
                System.out.println("           或导出 POLY_BUILDER_CODE 后重跑。");
            } else {
                System.out.println("builder code = " + builderCode);
            }

            LimitOrderArgsV2 limit = LimitOrderArgsV2.builder()
                    .tokenId(tokenId)
                    .price(price)
                    .size(size)
                    .side(side)
                    .builderCode(builderCode)
                    .build();
            SignedOrderV2 signed = clob.orderBuilder().createOrderV2(limit, opts).join();

            System.out.println("limit " + side + "  price=" + price + "  size=" + size);
            System.out.println("  maker(Safe)= " + signed.getOrder().getMaker().toHex());
            System.out.println("  signer(EOA)= " + signed.getOrder().getSigner().toHex());
            System.out.println("  makerAmt   = " + signed.getOrder().getMakerAmount());
            System.out.println("  takerAmt   = " + signed.getOrder().getTakerAmount());
            System.out.println("  sigType    = " + signed.getOrder().getSignatureType());
            System.out.println("  timestamp  = " + signed.getOrder().getTimestamp());
            System.out.println("  metadata   = " + signed.getOrder().getMetadata());
            System.out.println("  builder    = " + signed.getOrder().getBuilder());
            System.out.println("  signature  = " + signed.getSignature().substring(0, 20) + "...");

            if (!submit) {
                System.out.println("\n[dry-run] 设置 submit=true 才会真正 POST /order。");
                return;
            }

            PostOrderResponse resp;
            try {
                resp = clob.postOrderV2(signed, OrderType.GTC, false).join();
            } catch (java.util.concurrent.CompletionException ce) {
                Throwable cause = ce.getCause() == null ? ce : ce.getCause();
                if (cause instanceof ClobApiException api) {
                    // 默认的 message 只到 status+method+path，关键 body 要单独取
                    System.err.println("POST /order 失败 status=" + api.getStatusCode()
                            + " body=" + api.getBody());
                }
                throw ce;
            }
            System.out.println("\nPOST /order -> " + resp);

            if (resp.orderId() != null && !resp.orderId().isBlank()) {
                var cancel = clob.cancelOrder(resp.orderId()).join();
                System.out.println("cancel -> canceled=" + cancel.canceled()
                        + (cancel.notCanceled() != null && !cancel.notCanceled().isEmpty()
                                ? "  notCanceled=" + cancel.notCanceled() : ""));
            }
        }
    }
}
