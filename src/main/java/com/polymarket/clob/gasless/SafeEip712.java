package com.polymarket.clob.gasless;

import com.polymarket.clob.model.Address;
import org.web3j.crypto.Hash;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Safe (v1.3.0) 与 SafeProxyFactory 的 EIP-712 摘要计算。
 *
 * <p>之所以不用 web3j {@code StructuredDataEncoder}：</p>
 * <ul>
 *   <li><b>Safe v1.3.0</b> 的 domain 类型为 {@code EIP712Domain(uint256 chainId, address verifyingContract)}
 *       —— <b>没有 name 字段</b>，与 {@code StructuredDataEncoder} 默认假设不一致；</li>
 *   <li><b>SafeFactory CreateProxy</b> 的 domain 反而<b>有 name</b>（{@code "Polymarket Contract Proxy Factory"}）
 *       但<b>没有 version 字段</b>，依然是非标准布局；</li>
 *   <li>本 SDK 必须按字节级与 npm {@code @polymarket/builder-relayer-client} v0.0.6、Rust SDK
 *       完全一致，手写更可控。</li>
 * </ul>
 *
 * <p>两个入口：</p>
 * <ol>
 *   <li>{@link #safeTxHash} —— Safe.execTransaction 的 SafeTx 摘要（10 字段 struct）；</li>
 *   <li>{@link #createProxyHash} —— SafeProxyFactory.createProxy 的 CreateProxy 摘要（3 字段 struct）。</li>
 * </ol>
 *
 * <p>线程安全（无可变状态）。</p>
 */
public final class SafeEip712 {

    private static final byte[] EIP_191_PREFIX = new byte[]{0x19, 0x01};

    /** SafeTx struct typeHash 预计算缓存（v1.3.0 固定 10 字段）。 */
    private static final byte[] SAFE_TX_TYPE_HASH = Hash.sha3((""
            + "SafeTx(address to,uint256 value,bytes data,uint8 operation,"
            + "uint256 safeTxGas,uint256 baseGas,uint256 gasPrice,address gasToken,"
            + "address refundReceiver,uint256 nonce)").getBytes(StandardCharsets.UTF_8));

    /** SafeTx domain typeHash（无 name / version 字段）。 */
    private static final byte[] SAFE_TX_DOMAIN_TYPE_HASH = Hash.sha3((""
            + "EIP712Domain(uint256 chainId,address verifyingContract)").getBytes(StandardCharsets.UTF_8));

    /** SafeProxyFactory CreateProxy domain typeHash（无 version 字段）。 */
    private static final byte[] FACTORY_DOMAIN_TYPE_HASH = Hash.sha3((""
            + "EIP712Domain(string name,uint256 chainId,address verifyingContract)")
            .getBytes(StandardCharsets.UTF_8));

    /** SafeProxyFactory CreateProxy struct typeHash。 */
    private static final byte[] FACTORY_CREATE_PROXY_TYPE_HASH = Hash.sha3((""
            + "CreateProxy(address paymentToken,uint256 payment,address paymentReceiver)")
            .getBytes(StandardCharsets.UTF_8));

    /** SafeProxyFactory domain name（写死字符串的 keccak）。 */
    private static final byte[] FACTORY_DOMAIN_NAME_HASH =
            Hash.sha3("Polymarket Contract Proxy Factory".getBytes(StandardCharsets.UTF_8));

    private SafeEip712() {}

    /**
     * 计算 Safe v1.3.0 {@code execTransaction} 的 EIP-712 final hash（32 字节）。
     *
     * <p>等价于：</p>
     * <pre>
     * keccak256(0x19 0x01
     *           ‖ keccak256(EIP712Domain(uint256 chainId, address verifyingContract))
     *                       ‖ chainId(32) ‖ safe(32))
     *           ‖ keccak256(SafeTx(...10 字段...)
     *                       ‖ to(32) ‖ value(32)=0 ‖ keccak256(data) ‖ operation(32)
     *                       ‖ safeTxGas(32)=0 ‖ baseGas(32)=0 ‖ gasPrice(32)=0
     *                       ‖ gasToken(32)=0 ‖ refundReceiver(32)=0 ‖ nonce(32)))
     * </pre>
     *
     * <p>本 SDK 与 Polymarket relayer 完全对齐：{@code value/safeTxGas/baseGas/gasPrice/gasToken/
     * refundReceiver} 全部为 0，由 relayer 代付 gas，不设 refund——这一组常量是 wire 层硬编码，
     * 不开放参数化。</p>
     *
     * @param chainId   {@code 137} = Polygon、{@code 80002} = Amoy
     * @param safe      Safe 钱包地址（domain.verifyingContract）
     * @param to        execTransaction 的 target；多笔批次时是 {@link MultiSend#MULTISEND_CALL_ONLY}
     * @param data      target 上的 calldata
     * @param operation {@code 0=Call} / {@code 1=DelegateCall}
     * @param nonce     Safe 当前 nonce
     */
    public static byte[] safeTxHash(long chainId,
                                    Address safe,
                                    Address to,
                                    byte[] data,
                                    int operation,
                                    BigInteger nonce) {
        Objects.requireNonNull(safe, "safe");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(nonce, "nonce");
        if (operation != 0 && operation != 1) {
            throw new IllegalArgumentException("operation 必须 0=Call 或 1=DelegateCall：" + operation);
        }
        if (chainId <= 0) {
            throw new IllegalArgumentException("chainId 必须为正：" + chainId);
        }
        if (nonce.signum() < 0) {
            throw new IllegalArgumentException("nonce 不能为负：" + nonce);
        }

        byte[] domainSeparator = Hash.sha3(Words.concat(
                SAFE_TX_DOMAIN_TYPE_HASH,
                Words.leftPad32(BigInteger.valueOf(chainId)),
                Words.leftPad32(safe)));

        byte[] dataHash = Hash.sha3(data);
        byte[] structHash = Hash.sha3(Words.concat(
                SAFE_TX_TYPE_HASH,
                Words.leftPad32(to),
                Words.leftPad32(BigInteger.ZERO),                       // value
                dataHash,
                Words.leftPad32(BigInteger.valueOf(operation)),
                Words.leftPad32(BigInteger.ZERO),                       // safeTxGas
                Words.leftPad32(BigInteger.ZERO),                       // baseGas
                Words.leftPad32(BigInteger.ZERO),                       // gasPrice
                Words.leftPad32(Address.ZERO),                          // gasToken
                Words.leftPad32(Address.ZERO),                          // refundReceiver
                Words.leftPad32(nonce)));

        return Hash.sha3(Words.concat(EIP_191_PREFIX, domainSeparator, structHash));
    }

    /**
     * 计算 SafeProxyFactory.createProxy 的 EIP-712 final hash（32 字节）。
     *
     * <p>domain 含 {@code name="Polymarket Contract Proxy Factory"}（无 version）；struct 是
     * {@code CreateProxy(address paymentToken, uint256 payment, address paymentReceiver)}，三字段
     * 全部为 0/zero-address——relayer 不收 payment。本函数对外不暴露这三个常量参数。</p>
     */
    public static byte[] createProxyHash(long chainId, Address factory) {
        Objects.requireNonNull(factory, "factory");
        if (chainId <= 0) {
            throw new IllegalArgumentException("chainId 必须为正：" + chainId);
        }

        byte[] domainSeparator = Hash.sha3(Words.concat(
                FACTORY_DOMAIN_TYPE_HASH,
                FACTORY_DOMAIN_NAME_HASH,
                Words.leftPad32(BigInteger.valueOf(chainId)),
                Words.leftPad32(factory)));

        byte[] structHash = Hash.sha3(Words.concat(
                FACTORY_CREATE_PROXY_TYPE_HASH,
                Words.leftPad32(Address.ZERO),     // paymentToken
                Words.leftPad32(BigInteger.ZERO),  // payment
                Words.leftPad32(Address.ZERO)));   // paymentReceiver

        return Hash.sha3(Words.concat(EIP_191_PREFIX, domainSeparator, structHash));
    }
}
