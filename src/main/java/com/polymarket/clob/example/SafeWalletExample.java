package com.polymarket.clob.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.WalletDerivation;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.ChainId;
import com.polymarket.clob.model.ContractConfig;
import com.polymarket.clob.model.ContractRegistry;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.StaticStruct;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.StructuredDataEncoder;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthGasPrice;
import org.web3j.protocol.core.methods.response.EthGetCode;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

/**
 * Polymarket Safe 钱包部署 + 授权示例（端到端、链上自付 gas）。
 *
 * <p>用途：从一个 EOA 私钥出发，完成线上交易前的两件事：
 * <ol>
 *   <li><b>部署 Safe 代理钱包</b>：调用 Polymarket Safe Proxy Factory 的
 *       {@code createProxy(address,uint256,address,Sig)}（CREATE2 派生，与
 *       {@link WalletDerivation#deriveSafeWallet} 一致）；</li>
 *   <li><b>设置授权</b>：以 Safe 钱包身份依次给三家 Exchange 合约：
 *       <ul>
 *         <li>USDC（{@code 0x2791…84174}）→ {@code approve(spender, MAX_UINT256)}</li>
 *         <li>CTF（{@code 0x4D97…6045}）→ {@code setApprovalForAll(operator, true)}</li>
 *       </ul>
 *       授权事务通过 Safe 的 {@code execTransaction} 调度；因 EOA 既是 Safe 唯一 owner
 *       又是 {@code msg.sender}，使用 <b>pre-validated signature</b> 即可（v=1，无需再次 EIP-712 签名）。
 *   </li>
 * </ol>
 *
 * <p>本示例与官方 magic-safe-builder 的 relayer 版本相比：
 * <b>EOA 自付 gas</b>，不依赖任何 builder/relayer；适合 Java 服务端直接掌握私钥的场景。
 *
 * <h3>环境变量</h3>
 * <ul>
 *   <li>{@code CLOB_PRIVATE_KEY}（必填）：0x 前缀 32 字节 EOA 私钥；</li>
 *   <li>{@code CLOB_RPC_URL}（必填）：Polygon/Amoy 的 JSON-RPC 端点；</li>
 *   <li>{@code CLOB_CHAIN_ID}（可选，默认 {@code POLYGON}）：{@code POLYGON|AMOY|<int>}；</li>
 *   <li>{@code DRY_RUN}（可选，默认 0）：设为 1 仅打印计划，不发送任何交易；</li>
 *   <li>{@code APPROVE_CTF_DEPOSIT}（可选，默认 0）：设为 1 时额外把 USDC 授权给
 *       {@code ConditionalTokens} 自身（用于 split/merge，非纯交易场景才需要）。</li>
 * </ul>
 *
 * <h3>运行</h3>
 * <pre>{@code
 * export CLOB_PRIVATE_KEY=0xac0974...
 * export CLOB_RPC_URL=https://polygon-rpc.com
 * # 先空跑确认地址派生 / 链上状态
 * DRY_RUN=1 mvn -B compile exec:java \
 *     -Dexec.mainClass=com.polymarket.clob.example.SafeWalletExample
 * # 真正上链（需要 EOA 持有少量 MATIC 支付 gas）
 * mvn -B compile exec:java \
 *     -Dexec.mainClass=com.polymarket.clob.example.SafeWalletExample
 * }</pre>
 */
public final class SafeWalletExample {

    /** USDC（生产 Polygon），Amoy 上是测试 USDC。值取自 {@link ContractRegistry}。 */
    private static final BigInteger MAX_UINT256 = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);

    /** Polygon 上 1 gwei；用于在 RPC 返回 0 时的兜底。 */
    private static final BigInteger ONE_GWEI = BigInteger.valueOf(1_000_000_000L);

    /** 默认轮询间隔与最长等待。Polygon 出块 ~2s，60 次约 2 分钟，足够 finality。 */
    private static final long RECEIPT_POLL_INTERVAL_MS = 2_000L;
    private static final int RECEIPT_MAX_ATTEMPTS = 60;

    /** Safe execTransaction 中 operation 枚举：0=Call, 1=DelegateCall。 */
    private static final BigInteger OP_CALL = BigInteger.ZERO;

    private SafeWalletExample() {}

    public static void main(String[] args) throws Exception {
        // ---------- 1. 环境变量 ----------
        String privateKey = require("CLOB_PRIVATE_KEY");
        String rpcUrl = require("CLOB_RPC_URL");
        long chainId = parseChainId(System.getenv("CLOB_CHAIN_ID"));
        boolean dryRun = "1".equals(System.getenv("DRY_RUN"));
        boolean approveCtfDeposit = "1".equals(System.getenv("APPROVE_CTF_DEPOSIT"));

        // ---------- 2. 初始化 web3j / signer ----------
        Web3j web3 = Web3j.build(new HttpService(rpcUrl));
        Credentials creds = Credentials.create(stripHex(privateKey));
        LocalSigner signer = LocalSigner.fromPrivateKey(privateKey);

        try {
            Address safe = ensureDeployedAndApproved(
                    web3, creds, signer, chainId, dryRun, approveCtfDeposit);
            System.out.println("\n完成。Safe 钱包：" + safe.toHex());
        } finally {
            web3.shutdown();
        }
    }

    /**
     * 端到端编排入口：派生 Safe → 检测/部署 → 检测/补齐授权。<b>幂等</b>，可被
     * {@link EndToEndOnboardingExample} 等其它示例直接复用。
     *
     * <p>流程：
     * <ol>
     *   <li>从 {@code creds} 派生 EOA，再用 {@link WalletDerivation#deriveSafeWallet}
     *       计算应得的 Safe 地址；</li>
     *   <li>{@code eth_getCode} 探测 Safe 是否已部署；未部署 → 调工厂 {@code createProxy}（自付 gas）；</li>
     *   <li>逐个 spender 检查 USDC.allowance，未达 MAX/2 视为未授权 → 通过 {@code execTransaction} 补齐；</li>
     *   <li>逐个 operator 检查 CTF.isApprovedForAll，未授权 → 通过 {@code execTransaction} 补齐；</li>
     *   <li>{@code dryRun=true} 时仅打印计划，不发交易；返回值仍为预期 Safe 地址。</li>
     * </ol>
     *
     * @param web3                Polygon/Amoy JSON-RPC 客户端（调用方负责 shutdown）
     * @param creds               EOA Credentials（用于发起链上交易）
     * @param signer              LocalSigner（仅用于工厂 EIP-712 签名）
     * @param chainId             POLYGON | AMOY
     * @param dryRun              true 时不发交易，仅打印
     * @param approveCtfDeposit   true 时额外把 USDC 授权给 CTF 合约本身（split/merge 才需要）
     * @return 该 EOA 对应的 Safe 钱包地址（无论是否新部署）
     */
    public static Address ensureDeployedAndApproved(
            Web3j web3,
            Credentials creds,
            LocalSigner signer,
            long chainId,
            boolean dryRun,
            boolean approveCtfDeposit) throws Exception {

        Address eoa = Address.fromHex(creds.getAddress());

        // 加载合约配置
        Address safeFactory = ContractRegistry.walletConfig(chainId)
                .orElseThrow(() -> new IllegalStateException("不支持的 chainId=" + chainId))
                .safeFactory();
        ContractConfig std = ContractRegistry.contractConfig(chainId, false)
                .orElseThrow(() -> new IllegalStateException("standard 合约缺失 chainId=" + chainId));
        ContractConfig neg = ContractRegistry.contractConfig(chainId, true)
                .orElseThrow(() -> new IllegalStateException("neg-risk 合约缺失 chainId=" + chainId));
        Address usdc = std.collateral();
        Address ctf = std.conditionalTokens();
        Address ctfExchange = std.exchange();
        Address negRiskExchange = neg.exchange();
        Address negRiskAdapter = neg.negRiskAdapter()
                .orElseThrow(() -> new IllegalStateException("neg-risk adapter 缺失"));

        System.out.println("================ Polymarket Safe 钱包初始化 ================");
        System.out.println("ChainId      = " + chainId);
        System.out.println("EOA          = " + eoa.toHex());
        System.out.println("Dry-Run      = " + dryRun);
        System.out.println("safeFactory  = " + safeFactory.toHex());
        System.out.println("USDC         = " + usdc.toHex());
        System.out.println("CTF          = " + ctf.toHex());
        System.out.println("CTFExchange  = " + ctfExchange.toHex());
        System.out.println("NegExchange  = " + negRiskExchange.toHex());
        System.out.println("NegAdapter   = " + negRiskAdapter.toHex());

        // 派生 Safe 地址 + 链上探测
        Address safe = WalletDerivation.deriveSafeWallet(eoa, chainId)
                .orElseThrow(() -> new IllegalStateException("无法派生 Safe 地址，chainId=" + chainId));
        boolean deployed = isDeployed(web3, safe);
        System.out.println("\n--- Safe 地址派生 ---");
        System.out.println("Safe         = " + safe.toHex());
        System.out.println("Deployed?    = " + deployed);

        // 部署 Safe（如未部署）
        if (!deployed) {
            if (dryRun) {
                System.out.println("[DRY_RUN] 将调用 " + safeFactory.toHex()
                        + ".createProxy(address(0), 0, address(0), Sig)");
            } else {
                deploySafe(web3, creds, signer, chainId, safeFactory);
                deployed = isDeployed(web3, safe);
                if (!deployed) {
                    throw new IllegalStateException("createProxy 已 mined 但 Safe 仍未部署，请检查 RPC 一致性。");
                }
                System.out.println("Safe 已部署: " + safe.toHex());
            }
        }

        // 授权列表（与 rs-clob-client/examples/approvals.rs 对齐）
        List<NamedAddress> spenders = List.of(
                new NamedAddress("CTFExchange",       ctfExchange),
                new NamedAddress("NegRiskExchange",   negRiskExchange),
                new NamedAddress("NegRiskAdapter",    negRiskAdapter));
        List<NamedAddress> usdcSpenders = approveCtfDeposit
                ? List.of(
                        new NamedAddress("CTFContract",      ctf),
                        new NamedAddress("CTFExchange",      ctfExchange),
                        new NamedAddress("NegRiskExchange",  negRiskExchange),
                        new NamedAddress("NegRiskAdapter",   negRiskAdapter))
                : spenders;

        // USDC approve
        System.out.println("\n--- USDC.allowance(safe -> spender) ---");
        for (NamedAddress sp : usdcSpenders) {
            BigInteger cur = deployed ? readUsdcAllowance(web3, usdc, safe, sp.address()) : BigInteger.ZERO;
            boolean ok = cur.compareTo(MAX_UINT256.shiftRight(1)) > 0;
            System.out.printf("  %-18s = %s%s%n", sp.name(), cur, ok ? "  (OK)" : "");
            if (!ok) {
                if (dryRun) {
                    System.out.println("    [DRY_RUN] 将通过 Safe.execTransaction 对 USDC.approve("
                            + sp.address().toHex() + ", MAX) 上链");
                } else if (deployed) {
                    sendSafeApproveUsdc(web3, creds, safe, eoa, usdc, sp);
                }
            }
        }

        // CTF setApprovalForAll
        System.out.println("\n--- CTF.isApprovedForAll(safe -> operator) ---");
        for (NamedAddress op : spenders) {
            boolean cur = deployed && readCtfIsApprovedForAll(web3, ctf, safe, op.address());
            System.out.printf("  %-18s = %s%n", op.name(), cur);
            if (!cur) {
                if (dryRun) {
                    System.out.println("    [DRY_RUN] 将通过 Safe.execTransaction 对 CTF.setApprovalForAll("
                            + op.address().toHex() + ", true) 上链");
                } else if (deployed) {
                    sendSafeSetApprovalForAll(web3, creds, safe, eoa, ctf, op);
                }
            }
        }

        return safe;
    }

    // ============================================================
    //  Safe 部署（调用工厂 createProxy + EIP-712 签名）
    // ============================================================

    /**
     * 使用 EOA 发送 {@code createProxy(address(0), 0, address(0), Sig)}：
     * <ul>
     *   <li>paymentToken=0、payment=0：表示不使用 relayer 报销机制；EOA 自付 gas；</li>
     *   <li>Sig 是对工厂 EIP-712 域 {@code CreateProxy} 的签名 —— 工厂用它 ECDSA.recover
     *       出 owner 地址，然后以该 owner 部署 1-of-1 GnosisSafe；</li>
     *   <li>所以 EOA 既是签名者也是 owner，结果与 {@link WalletDerivation#deriveSafeWallet} 派生的地址完全一致。</li>
     * </ul>
     */
    private static void deploySafe(
            Web3j web3,
            Credentials creds,
            LocalSigner signer,
            long chainId,
            Address safeFactory) throws Exception {

        // 1) EIP-712 摘要
        byte[] digest = factoryCreateProxyDigest(chainId, safeFactory,
                Address.ZERO, BigInteger.ZERO, Address.ZERO);

        // 2) 签名（65 字节 r||s||v，v=27/28，与 ECDSA.recover 一致）
        byte[] sig65 = signer.signHash(digest).join();
        if (sig65.length != 65) throw new IllegalStateException("签名长度异常：" + sig65.length);

        byte[] r = sub(sig65, 0, 32);
        byte[] s = sub(sig65, 32, 32);
        BigInteger v = BigInteger.valueOf(sig65[64] & 0xff);

        // 3) calldata: createProxy(address(0), 0, address(0), Sig{v,r,s})
        Function fn = new Function(
                "createProxy",
                List.of(
                        new org.web3j.abi.datatypes.Address(160, Address.ZERO.toLowerHex()),
                        new Uint256(BigInteger.ZERO),
                        new org.web3j.abi.datatypes.Address(160, Address.ZERO.toLowerHex()),
                        new StaticStruct(
                                new Uint8(v),
                                new Bytes32(r),
                                new Bytes32(s))),
                List.of());
        String data = FunctionEncoder.encode(fn);

        // 4) 发送 + 等待 receipt（gasLimit 给足，Safe 部署约 250–300k gas）
        BigInteger gasLimit = BigInteger.valueOf(450_000L);
        TransactionReceipt rcpt = sendTx(web3, creds, chainId, safeFactory, BigInteger.ZERO, data, gasLimit);
        System.out.println("createProxy tx = " + rcpt.getTransactionHash()
                + "  status=" + rcpt.getStatus() + "  gasUsed=" + rcpt.getGasUsed());
        if (!"0x1".equalsIgnoreCase(rcpt.getStatus())) {
            throw new IllegalStateException("createProxy 失败 status=" + rcpt.getStatus());
        }
    }

    /**
     * 计算工厂的 EIP-712 摘要：
     * <pre>
     * domain = {
     *   name: "Polymarket Contract Proxy Factory",
     *   chainId,
     *   verifyingContract: factory
     * }
     * primaryType = "CreateProxy"
     * message = { paymentToken, payment, paymentReceiver }
     * </pre>
     * 注意：工厂 domain <b>没有</b> {@code version} 字段，与 ClobAuth/Order 不同。
     */
    private static byte[] factoryCreateProxyDigest(
            long chainId,
            Address factory,
            Address paymentToken,
            BigInteger payment,
            Address paymentReceiver) throws Exception {

        ObjectMapper m = new ObjectMapper();
        ObjectNode root = m.createObjectNode();

        ObjectNode types = root.putObject("types");
        ArrayNode domainType = types.putArray("EIP712Domain");
        addField(m, domainType, "name", "string");
        addField(m, domainType, "chainId", "uint256");
        addField(m, domainType, "verifyingContract", "address");

        ArrayNode createProxyType = types.putArray("CreateProxy");
        addField(m, createProxyType, "paymentToken", "address");
        addField(m, createProxyType, "payment", "uint256");
        addField(m, createProxyType, "paymentReceiver", "address");

        root.put("primaryType", "CreateProxy");

        ObjectNode domain = root.putObject("domain");
        domain.put("name", "Polymarket Contract Proxy Factory");
        domain.put("chainId", chainId);
        domain.put("verifyingContract", factory.toLowerHex());

        ObjectNode message = root.putObject("message");
        message.put("paymentToken", paymentToken.toLowerHex());
        message.put("payment", payment.toString());
        message.put("paymentReceiver", paymentReceiver.toLowerHex());

        return new StructuredDataEncoder(m.writeValueAsString(root)).hashStructuredData();
    }

    // ============================================================
    //  Safe execTransaction（pre-validated 签名版本）
    // ============================================================

    /**
     * 调度 Safe 执行 USDC.approve(spender, MAX_UINT256)。
     */
    private static void sendSafeApproveUsdc(
            Web3j web3,
            Credentials creds,
            Address safe,
            Address eoaOwner,
            Address usdc,
            NamedAddress spender) throws Exception {

        Function approveFn = new Function(
                "approve",
                List.of(
                        new org.web3j.abi.datatypes.Address(160, spender.address().toLowerHex()),
                        new Uint256(MAX_UINT256)),
                List.of(new TypeReference<Bool>() {}));
        byte[] inner = Numeric.hexStringToByteArray(FunctionEncoder.encode(approveFn));

        TransactionReceipt rcpt = execSafeTx(web3, creds, safe, eoaOwner, usdc, inner,
                "USDC.approve(" + spender.name() + ")");
        System.out.println("    -> tx=" + rcpt.getTransactionHash()
                + "  status=" + rcpt.getStatus()
                + "  gas=" + rcpt.getGasUsed());
    }

    /**
     * 调度 Safe 执行 CTF.setApprovalForAll(operator, true)。
     */
    private static void sendSafeSetApprovalForAll(
            Web3j web3,
            Credentials creds,
            Address safe,
            Address eoaOwner,
            Address ctf,
            NamedAddress operator) throws Exception {

        Function setApprovalFn = new Function(
                "setApprovalForAll",
                List.of(
                        new org.web3j.abi.datatypes.Address(160, operator.address().toLowerHex()),
                        new Bool(true)),
                List.of());
        byte[] inner = Numeric.hexStringToByteArray(FunctionEncoder.encode(setApprovalFn));

        TransactionReceipt rcpt = execSafeTx(web3, creds, safe, eoaOwner, ctf, inner,
                "CTF.setApprovalForAll(" + operator.name() + ")");
        System.out.println("    -> tx=" + rcpt.getTransactionHash()
                + "  status=" + rcpt.getStatus()
                + "  gas=" + rcpt.getGasUsed());
    }

    /**
     * 用 EOA 直接调用 {@code Safe.execTransaction(...)}：
     *
     * <p>由于 EOA 既是 Safe 唯一 owner 又是本笔交易的 {@code msg.sender}，可以使用
     * <b>pre-validated signature</b>（v=1）：Safe 只校验 {@code r 后 20 字节 == owner}
     * 且 {@code msg.sender == owner}，无需我们再签 SafeTx hash。这比走标准 EIP-712
     * SafeTx 路径少一次签名摘要，更适合自己掌握私钥的场景。
     *
     * <p>signatures 字段 65 字节布局：
     * <pre>
     * [0..12)  全 0
     * [12..32) owner address (20 字节)
     * [32..64) 全 0
     * [64]     0x01
     * </pre>
     */
    private static TransactionReceipt execSafeTx(
            Web3j web3,
            Credentials creds,
            Address safe,
            Address eoaOwner,
            Address target,
            byte[] innerCalldata,
            String description) throws Exception {

        byte[] preValidatedSig = new byte[65];
        // r = pad32(owner)
        System.arraycopy(eoaOwner.toBytes(), 0, preValidatedSig, 12, 20);
        // s = 0（已默认）
        preValidatedSig[64] = 0x01;

        Function execTx = new Function(
                "execTransaction",
                List.of(
                        new org.web3j.abi.datatypes.Address(160, target.toLowerHex()),
                        new Uint256(BigInteger.ZERO),                     // value
                        new DynamicBytes(innerCalldata),                  // data
                        new Uint8(OP_CALL),                               // operation = Call
                        new Uint256(BigInteger.ZERO),                     // safeTxGas
                        new Uint256(BigInteger.ZERO),                     // baseGas
                        new Uint256(BigInteger.ZERO),                     // gasPrice
                        new org.web3j.abi.datatypes.Address(160, Address.ZERO.toLowerHex()), // gasToken
                        new org.web3j.abi.datatypes.Address(160, Address.ZERO.toLowerHex()), // refundReceiver
                        new DynamicBytes(preValidatedSig)),
                List.of(new TypeReference<Bool>() {}));
        String data = FunctionEncoder.encode(execTx);

        // approve / setApprovalForAll 都是 ~50k gas，Safe 调度本身约 30k overhead，按 200k 余量
        BigInteger gasLimit = BigInteger.valueOf(220_000L);
        long chainId = web3.ethChainId().send().getChainId().longValueExact();
        System.out.println("  exec: " + description + " via Safe " + safe.toHex());
        return sendTx(web3, creds, chainId, safe, BigInteger.ZERO, data, gasLimit);
    }

    // ============================================================
    //  只读 RPC：getCode / allowance / isApprovedForAll
    // ============================================================

    private static boolean isDeployed(Web3j web3, Address addr) throws Exception {
        EthGetCode resp = web3.ethGetCode(addr.toLowerHex(), DefaultBlockParameterName.LATEST).send();
        if (resp.hasError()) {
            throw new IllegalStateException("eth_getCode 失败: " + resp.getError().getMessage());
        }
        String code = resp.getCode();
        return code != null && code.length() > 2 && !"0x".equalsIgnoreCase(code);
    }

    private static BigInteger readUsdcAllowance(Web3j web3, Address usdc, Address owner, Address spender)
            throws Exception {
        Function fn = new Function(
                "allowance",
                List.of(
                        new org.web3j.abi.datatypes.Address(160, owner.toLowerHex()),
                        new org.web3j.abi.datatypes.Address(160, spender.toLowerHex())),
                List.of(new TypeReference<Uint256>() {}));
        BigInteger v = (BigInteger) callOne(web3, usdc, fn);
        return v == null ? BigInteger.ZERO : v;
    }

    private static boolean readCtfIsApprovedForAll(Web3j web3, Address ctf, Address owner, Address operator)
            throws Exception {
        Function fn = new Function(
                "isApprovedForAll",
                List.of(
                        new org.web3j.abi.datatypes.Address(160, owner.toLowerHex()),
                        new org.web3j.abi.datatypes.Address(160, operator.toLowerHex())),
                List.of(new TypeReference<Bool>() {}));
        Object v = callOne(web3, ctf, fn);
        return v instanceof Boolean b && b;
    }

    private static Object callOne(Web3j web3, Address to, Function fn) throws Exception {
        String data = FunctionEncoder.encode(fn);
        EthCall resp = web3.ethCall(
                Transaction.createEthCallTransaction(null, to.toLowerHex(), data),
                DefaultBlockParameterName.LATEST).send();
        if (resp.hasError()) {
            throw new IllegalStateException("eth_call 失败: " + resp.getError().getMessage());
        }
        List<Type<?>> decoded = castDecoded(FunctionReturnDecoder.decode(resp.getValue(), fn.getOutputParameters()));
        if (decoded.isEmpty()) return null;
        return decoded.get(0).getValue();
    }

    // ============================================================
    //  写交易 + 等待 receipt
    // ============================================================

    /**
     * 构造 legacy 交易（type=0）→ EOA 私钥本地签名 → eth_sendRawTransaction → 轮询 receipt。
     * 主动拒绝 type=2（EIP-1559）以避免不同 RPC 对 maxFee 的差异；Polygon 上 legacy 同样合法。
     */
    private static TransactionReceipt sendTx(
            Web3j web3,
            Credentials creds,
            long chainId,
            Address to,
            BigInteger value,
            String data,
            BigInteger gasLimit) throws Exception {

        BigInteger nonce = web3.ethGetTransactionCount(creds.getAddress(), DefaultBlockParameterName.PENDING)
                .send().getTransactionCount();
        BigInteger gasPrice = fetchGasPriceWithFallback(web3);

        RawTransaction tx = RawTransaction.createTransaction(
                nonce, gasPrice, gasLimit, to.toLowerHex(), value, data);
        byte[] signed = TransactionEncoder.signMessage(tx, chainId, creds);
        EthSendTransaction send = web3.ethSendRawTransaction(Numeric.toHexString(signed)).send();
        if (send.hasError()) {
            throw new IllegalStateException("eth_sendRawTransaction 失败: "
                    + send.getError().getMessage()
                    + (send.getError().getData() != null ? " data=" + send.getError().getData() : ""));
        }
        String hash = send.getTransactionHash();
        System.out.println("  sent tx=" + hash + " (nonce=" + nonce + " gasPrice=" + gasPrice
                + " gasLimit=" + gasLimit + ")");
        return waitForReceipt(web3, hash);
    }

    private static BigInteger fetchGasPriceWithFallback(Web3j web3) throws Exception {
        EthGasPrice resp = web3.ethGasPrice().send();
        BigInteger gp = resp.hasError() ? null : resp.getGasPrice();
        if (gp == null || gp.signum() <= 0) {
            // Polygon 实测在 30 gwei 左右，给保守起步价；调用方可在外部覆盖
            return BigInteger.valueOf(30L).multiply(ONE_GWEI);
        }
        // 加 20% 提速防被替换；Polygon 公链实践中常见做法
        return gp.add(gp.divide(BigInteger.valueOf(5)));
    }

    private static TransactionReceipt waitForReceipt(Web3j web3, String txHash) throws Exception {
        for (int i = 0; i < RECEIPT_MAX_ATTEMPTS; i++) {
            var resp = web3.ethGetTransactionReceipt(txHash).send();
            if (resp.getTransactionReceipt().isPresent()) {
                return resp.getTransactionReceipt().get();
            }
            Thread.sleep(RECEIPT_POLL_INTERVAL_MS);
        }
        throw new IllegalStateException("等待 " + (RECEIPT_MAX_ATTEMPTS * RECEIPT_POLL_INTERVAL_MS / 1000)
                + "s 后仍未拿到 receipt: " + txHash);
    }

    // ============================================================
    //  小工具
    // ============================================================

    private record NamedAddress(String name, Address address) {}

    private static byte[] sub(byte[] src, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(src, off, out, 0, len);
        return out;
    }

    private static String stripHex(String h) {
        return (h.startsWith("0x") || h.startsWith("0X")) ? h.substring(2) : h;
    }

    private static String require(String env) {
        String v = System.getenv(env);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("环境变量 " + env + " 未设置");
        }
        return v;
    }

    private static long parseChainId(String raw) {
        if (raw == null || raw.isBlank() || "POLYGON".equalsIgnoreCase(raw)) {
            return ChainId.POLYGON;
        }
        if ("AMOY".equalsIgnoreCase(raw)) return ChainId.AMOY;
        return Long.parseLong(raw);
    }

    private static void addField(ObjectMapper m, ArrayNode arr, String name, String type) {
        ObjectNode o = m.createObjectNode();
        o.put("name", name);
        o.put("type", type);
        arr.add(o);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<Type<?>> castDecoded(List<Type> raw) {
        return (List) raw;
    }

    @SuppressWarnings("unused")
    private static String hex(byte[] b) {
        return "0x" + HexFormat.of().formatHex(b);
    }

    @SuppressWarnings("unused")
    private static String utf8(String s) {
        // 仅用于调试 EIP-712 名字串的可读化
        return new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unused")
    private static long currentNonce(Web3j web3, String addr) throws Exception {
        EthGetTransactionCount r = web3.ethGetTransactionCount(addr, DefaultBlockParameterName.LATEST).send();
        return r.getTransactionCount().longValueExact();
    }
}
