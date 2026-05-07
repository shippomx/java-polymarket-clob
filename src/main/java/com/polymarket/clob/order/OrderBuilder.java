package com.polymarket.clob.order;

import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.model.Address;

import java.math.BigInteger;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 订单构造 + 签名入口。组合 {@link OrderRounding}、{@link Order} 装配、{@link EIP712OrderSigner}
 * 的签名路径，功能对齐 py-clob-client 的 {@code OrderBuilder.create_order} /
 * {@code create_market_order}。
 *
 * <p>无可变状态，线程安全。所有方法返回 {@link CompletableFuture}——未来若接入
 * {@code RemoteSigner}，对上层保持一致形态。</p>
 */
public final class OrderBuilder {

    private final long chainId;
    private final Signer signer;
    private final Address funder;
    private final SignatureType signatureType;
    private final SaltSource saltSource;

    public OrderBuilder(long chainId,
                        Signer signer,
                        Address funder,
                        SignatureType signatureType,
                        SaltSource saltSource) {
        this.chainId = chainId;
        this.signer = Objects.requireNonNull(signer, "signer");
        this.funder = Objects.requireNonNull(funder, "funder");
        this.signatureType = Objects.requireNonNull(signatureType, "signatureType");
        this.saltSource = Objects.requireNonNull(saltSource, "saltSource");
    }

    /** 使用默认 {@link SaltSource#secureRandom()} 的便捷构造。 */
    public OrderBuilder(long chainId,
                        Signer signer,
                        Address funder,
                        SignatureType signatureType) {
        this(chainId, signer, funder, signatureType, SaltSource.secureRandom());
    }

    /** 构造并签名限价单。 */
    public CompletableFuture<SignedOrder> createOrder(LimitOrderArgs args, CreateOrderOptions options) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(options, "options");
        OrderRounding.Amounts amts = OrderRounding.limit(
                args.getSide(), args.getSize(), args.getPrice(), options.tickSize().rounding());

        Order order = assembleOrder(
                amts,
                args.getTokenId(),
                args.getSide(),
                args.getFeeRateBps(),
                args.getNonce(),
                BigInteger.valueOf(args.getExpiration()),
                args.getTaker());

        return EIP712OrderSigner.sign(signer, order, chainId, options.negRisk());
    }

    /** 构造并签名市价单（{@code expiration} 强制为 0）。 */
    public CompletableFuture<SignedOrder> createMarketOrder(MarketOrderArgs args, CreateOrderOptions options) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(options, "options");
        OrderRounding.Amounts amts = OrderRounding.market(
                args.getSide(), args.getAmount(), args.getPrice(), options.tickSize().rounding());

        Order order = assembleOrder(
                amts,
                args.getTokenId(),
                args.getSide(),
                args.getFeeRateBps(),
                args.getNonce(),
                BigInteger.ZERO,
                args.getTaker());

        return EIP712OrderSigner.sign(signer, order, chainId, options.negRisk());
    }

    /** 聚合 {@link Order} 装配逻辑；供限价与市价共用，避免字段漂移。 */
    private Order assembleOrder(OrderRounding.Amounts amts,
                                BigInteger tokenId,
                                Side side,
                                int feeRateBps,
                                BigInteger nonce,
                                BigInteger expiration,
                                Address taker) {
        if (tokenId == null || tokenId.signum() < 0) {
            throw new IllegalArgumentException("tokenId must be non-negative: " + tokenId);
        }
        if (feeRateBps < 0) {
            throw new IllegalArgumentException("feeRateBps must be non-negative: " + feeRateBps);
        }
        if (nonce == null || nonce.signum() < 0) {
            throw new IllegalArgumentException("nonce must be non-negative: " + nonce);
        }

        return Order.builder()
                .salt(saltSource.next())
                .maker(funder)
                .signer(signer.address())
                .taker(taker == null ? Address.ZERO : taker)
                .tokenId(tokenId)
                .makerAmount(amts.maker())
                .takerAmount(amts.taker())
                .expiration(expiration == null ? BigInteger.ZERO : expiration)
                .nonce(nonce)
                .feeRateBps(BigInteger.valueOf(feeRateBps))
                .side(side)
                .signatureType(signatureType)
                .build();
    }

    // ============================================================
    // V2 路径（2026-04-28 CTF Exchange v2 上线后）
    // ============================================================

    /**
     * 构造并签名 V2 限价单。与 V1 {@link #createOrder} 的区别：
     * <ul>
     *   <li>verifyingContract 走 {@link com.polymarket.clob.model.ContractRegistry#exchangeV2}；</li>
     *   <li>EIP-712 签名 struct <b>不含</b> {@code taker / expiration / nonce / feeRateBps}；</li>
     *   <li>必带 {@code timestamp / metadata / builder} 三个新字段；
     *       timestamp 由 {@link System#currentTimeMillis()} 注入；</li>
     *   <li>{@code expiration} 仍出现在 wire 上（用于服务端时间限制），但不入签名。</li>
     * </ul>
     */
    public CompletableFuture<SignedOrderV2> createOrderV2(LimitOrderArgsV2 args, CreateOrderOptions options) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(options, "options");
        OrderRounding.Amounts amts = OrderRounding.limit(
                args.getSide(), args.getSize(), args.getPrice(), options.tickSize().rounding());

        OrderV2 order = assembleOrderV2(
                amts,
                args.getTokenId(),
                args.getSide(),
                BigInteger.valueOf(args.getExpiration()),
                args.getBuilderCode(),
                args.getMetadata());

        if (signatureType == SignatureType.POLY_1271) {
            return Pol1271OrderSigner.sign(signer, order, chainId, options.negRisk());
        }
        return EIP712OrderSigner.signV2(signer, order, chainId, options.negRisk());
    }

    /** 构造并签名 V2 市价单（{@code expiration} 强制为 0）。 */
    public CompletableFuture<SignedOrderV2> createMarketOrderV2(MarketOrderArgsV2 args, CreateOrderOptions options) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(options, "options");
        OrderRounding.Amounts amts = OrderRounding.market(
                args.getSide(), args.getAmount(), args.getPrice(), options.tickSize().rounding());

        OrderV2 order = assembleOrderV2(
                amts,
                args.getTokenId(),
                args.getSide(),
                BigInteger.ZERO,
                args.getBuilderCode(),
                args.getMetadata());

        if (signatureType == SignatureType.POLY_1271) {
            return Pol1271OrderSigner.sign(signer, order, chainId, options.negRisk());
        }
        return EIP712OrderSigner.signV2(signer, order, chainId, options.negRisk());
    }

    /** 聚合 V2 {@link OrderV2} 装配逻辑；供限价与市价共用。 */
    private OrderV2 assembleOrderV2(OrderRounding.Amounts amts,
                                    BigInteger tokenId,
                                    Side side,
                                    BigInteger expiration,
                                    String builderCode,
                                    String metadata) {
        if (tokenId == null || tokenId.signum() < 0) {
            throw new IllegalArgumentException("tokenId must be non-negative: " + tokenId);
        }
        validateBytes32(builderCode, "builderCode");
        validateBytes32(metadata, "metadata");

        Address signerField = signatureType == SignatureType.POLY_1271 ? funder : signer.address();

        return OrderV2.builder()
                .salt(saltSource.next())
                .maker(funder)
                .signer(signerField)
                .tokenId(tokenId)
                .makerAmount(amts.maker())
                .takerAmount(amts.taker())
                .side(side)
                .signatureType(signatureType)
                .expiration(expiration == null ? BigInteger.ZERO : expiration)
                .timestamp(BigInteger.valueOf(System.currentTimeMillis()))
                .metadata(metadata)
                .builder(builderCode)
                .build();
    }

    private static void validateBytes32(String hex, String name) {
        Objects.requireNonNull(hex, name);
        if (!hex.startsWith("0x") || hex.length() != 66) {
            throw new IllegalArgumentException(
                    name + " must be 0x-prefixed 32-byte hex (66 chars), got length " + hex.length());
        }
    }

    // Getters for测试和上层透出
    public long chainId() { return chainId; }

    public Address funder() { return funder; }

    public SignatureType signatureType() { return signatureType; }
}
