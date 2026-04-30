package com.polymarket.clob.order;

import com.polymarket.clob.auth.Eip712TypedData;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.exception.ClobSignatureException;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.ContractConfig;
import com.polymarket.clob.model.ContractRegistry;

import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 订单 EIP-712 签名器。负责：
 *
 * <ol>
 *   <li>根据 {@code chainId} + {@code negRisk} 从 {@link ContractRegistry} 取 Exchange 地址
 *       作为 EIP-712 domain 的 {@code verifyingContract}；</li>
 *   <li>委托 {@link Eip712TypedData#hashOrder} 得到 32 字节 struct hash；</li>
 *   <li>调用 {@link Signer#signHash(byte[])} 拿到 65 字节 {@code r||s||v}；</li>
 *   <li>打包成 {@link SignedOrder}。</li>
 * </ol>
 *
 * <p>与 python-order-utils 的 {@code OrderBuilder.build_signed_order} 功能等价，区别仅在于
 * 签名本身由外部 {@link Signer} 出（支持 {@code LocalSigner} / 未来 {@code RemoteSigner}）。</p>
 *
 * <p>无可变状态；所有成员静态，无需实例化。</p>
 */
public final class EIP712OrderSigner {

    private EIP712OrderSigner() {}

    /**
     * 计算 Order 的 EIP-712 struct hash（32 字节）。
     *
     * @param order   待签名订单；不会被修改
     * @param chainId 域 chainId（例：Polygon 137 / Amoy 80002）
     * @param negRisk 多结果市场（Neg Risk CTF Exchange）=true；普通 CTF Exchange=false
     * @throws ClobSignatureException 无法解析 verifyingContract（链 ID 未注册）
     */
    public static byte[] hash(Order order, long chainId, boolean negRisk) {
        Objects.requireNonNull(order, "order");
        ContractConfig cfg = ContractRegistry.contractConfig(chainId, negRisk)
                .orElseThrow(() -> new ClobSignatureException(
                        "Unsupported chainId " + chainId + " (negRisk=" + negRisk + ")"));
        return Eip712TypedData.hashOrder(order, chainId, cfg.exchange());
    }

    /** 明确指定 {@code verifyingContract} 的重载，主要给跨市场批量测试用。 */
    public static byte[] hash(Order order, long chainId, Address verifyingContract) {
        return Eip712TypedData.hashOrder(order, chainId, verifyingContract);
    }

    /**
     * 计算 v2 Order 的 EIP-712 struct hash（32 字节）。
     *
     * <p>verifyingContract 取自 {@link ContractRegistry#exchangeV2(long, boolean)}：
     * 普通市场用 {@code exchangeV2}，negRisk 市场用 {@code negRiskExchangeV2}。</p>
     *
     * @throws ClobSignatureException 当链 ID 未注册或没有 V2 部署时
     */
    public static byte[] hashV2(OrderV2 order, long chainId, boolean negRisk) {
        Objects.requireNonNull(order, "order");
        Address verifyingContract = ContractRegistry.exchangeV2(chainId, negRisk)
                .orElseThrow(() -> new ClobSignatureException(
                        "Unsupported V2 exchange for chainId " + chainId + " (negRisk=" + negRisk + ")"));
        return Eip712TypedData.hashOrderV2(order, chainId, verifyingContract);
    }

    /** 明确指定 {@code verifyingContract} 的 V2 重载，主要给跨市场批量测试用。 */
    public static byte[] hashV2(OrderV2 order, long chainId, Address verifyingContract) {
        return Eip712TypedData.hashOrderV2(order, chainId, verifyingContract);
    }

    /**
     * 签名并产出 {@link SignedOrderV2}（v2 路径）。{@link SignatureType#POLY_1271} 是 v2 唯一支持。
     *
     * <p>语义与 V1 {@link #sign(Signer, Order, long, boolean)} 一致：
     * 失败通过 {@link CompletableFuture#isCompletedExceptionally()} 暴露。</p>
     */
    public static CompletableFuture<SignedOrderV2> signV2(Signer signer,
                                                          OrderV2 order,
                                                          long chainId,
                                                          boolean negRisk) {
        Objects.requireNonNull(signer, "signer");
        try {
            byte[] digest = hashV2(order, chainId, negRisk);
            return signer.signHash(digest).thenApply(sig -> {
                if (sig == null || sig.length != 65) {
                    throw new ClobSignatureException(
                            "Signer returned non-65-byte signature (length=" + (sig == null ? -1 : sig.length) + ")");
                }
                String sigHex = "0x" + HexFormat.of().formatHex(sig);
                return SignedOrderV2.of(order, sigHex);
            });
        } catch (ClobSignatureException e) {
            return CompletableFuture.failedFuture(e);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(
                    new ClobSignatureException("Failed to hash V2 order for signing", e));
        }
    }

    /**
     * 签名并产出 {@link SignedOrder}。失败（如链 ID 未知 / 签名器异常）通过返回的
     * {@link CompletableFuture#isCompletedExceptionally()} 暴露。
     *
     * @param signer  本地或远程签名器，{@code signer.address()} 应等于 {@code order.signer()}
     * @param order   待签名订单
     * @param chainId 域 chainId
     * @param negRisk 见 {@link #hash(Order, long, boolean)}
     */
    public static CompletableFuture<SignedOrder> sign(Signer signer,
                                                      Order order,
                                                      long chainId,
                                                      boolean negRisk) {
        Objects.requireNonNull(signer, "signer");
        try {
            byte[] digest = hash(order, chainId, negRisk);
            return signer.signHash(digest).thenApply(sig -> {
                if (sig == null || sig.length != 65) {
                    throw new ClobSignatureException(
                            "Signer returned non-65-byte signature (length=" + (sig == null ? -1 : sig.length) + ")");
                }
                String sigHex = "0x" + HexFormat.of().formatHex(sig);
                return SignedOrder.of(order, sigHex);
            });
        } catch (ClobSignatureException e) {
            return CompletableFuture.failedFuture(e);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(
                    new ClobSignatureException("Failed to hash order for signing", e));
        }
    }
}
