package com.polymarket.clob.example;

import com.polymarket.clob.AuthenticatedClobClient;
import com.polymarket.clob.ClobClient;
import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.model.ChainId;
import com.polymarket.clob.order.CreateOrderOptions;
import com.polymarket.clob.order.LimitOrderArgs;
import com.polymarket.clob.order.MarketOrderArgs;
import com.polymarket.clob.order.OpenOrderParams;
import com.polymarket.clob.order.OrderType;
import com.polymarket.clob.order.PostOrderResponse;
import com.polymarket.clob.order.TickSize;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Optional;

/**
 * Plan 3 示例：限价单 / 市价单 / 查询 / 批量 / 撤单。
 *
 * <p>为避免意外下单，本示例默认走 {@code dry-run} 模式：仅构造并打印订单内容，
 * 不会真正 POST 到服务器。设置 {@code CLOB_SUBMIT=1} 后才会走网络。</p>
 *
 * <p>环境变量：
 * <ul>
 *   <li>{@code CLOB_PRIVATE_KEY}（必填）</li>
 *   <li>{@code CLOB_TOKEN_ID}（必填，订单目标 ERC-1155 token id）</li>
 *   <li>{@code CLOB_ENDPOINT} / {@code CLOB_CHAIN_ID} / {@code CLOB_SIGNATURE_TYPE}（同 {@link AuthenticatedExample}）</li>
 *   <li>{@code CLOB_SUBMIT}（可选，{@code 1} 时提交真实请求）</li>
 * </ul>
 */
public final class TradingExample {

    private TradingExample() {}

    public static void main(String[] args) {
        String pk = require("CLOB_PRIVATE_KEY");
        String tokenIdStr = require("CLOB_TOKEN_ID");
        String endpoint = Optional.ofNullable(System.getenv("CLOB_ENDPOINT"))
                .orElse("https://clob.polymarket.com");
        long chainId = parseChainId(System.getenv("CLOB_CHAIN_ID"));
        SignatureType sigType = parseSignatureType(System.getenv("CLOB_SIGNATURE_TYPE"));
        boolean submit = "1".equals(System.getenv("CLOB_SUBMIT"));

        BigInteger tokenId = new BigInteger(tokenIdStr);
        Signer signer = LocalSigner.fromPrivateKey(pk);

        try (ClobClient base = ClobClient.builder()
                .endpoint(endpoint)
                .chainId(chainId)
                .build()) {

            AuthenticatedClobClient client = base
                    .authenticate(signer, sigType, BigInteger.ZERO)
                    .join();

            TickSize tick = client.market().getTickSize(tokenIdStr).join();
            boolean negRisk = client.market().getNegRisk(tokenIdStr).join();
            CreateOrderOptions opts = CreateOrderOptions.of(tick, negRisk);
            System.out.printf("token=%s  tick=%s  negRisk=%s%n", tokenIdStr, tick.wireValue(), negRisk);

            // ---- 限价单（dry-run 只构造，不 post） ----
            LimitOrderArgs limit = LimitOrderArgs.builder()
                    .tokenId(tokenId)
                    .price(new BigDecimal("0.5"))
                    .size(new BigDecimal("5"))
                    .side(Side.BUY)
                    .build();
            var signedLimit = client.orderBuilder().createOrder(limit, opts).join();
            System.out.println("limit buy    maker=" + signedLimit.getOrder().getMakerAmount()
                    + " taker=" + signedLimit.getOrder().getTakerAmount()
                    + " sig=" + signedLimit.getSignature().substring(0, 10) + "...");

            // ---- 市价单 ----
            MarketOrderArgs market = MarketOrderArgs.builder()
                    .tokenId(tokenId)
                    .amount(new BigDecimal("10"))
                    .price(new BigDecimal("0.5"))
                    .side(Side.BUY)
                    .build();
            var signedMarket = client.orderBuilder().createMarketOrder(market, opts).join();
            System.out.println("market FOK   maker=" + signedMarket.getOrder().getMakerAmount()
                    + " taker=" + signedMarket.getOrder().getTakerAmount());

            if (!submit) {
                System.out.println("\n[dry-run] 设置 CLOB_SUBMIT=1 以提交订单。");
                return;
            }

            PostOrderResponse resp = client.postOrder(signedLimit, OrderType.GTC, false).join();
            System.out.println("POST /order -> " + resp);

            long open = client.getOpenOrders(
                            OpenOrderParams.builder().assetId(tokenIdStr).build())
                    .count();
            System.out.println("open orders on token = " + open);

            if (resp.orderId() != null) {
                var cancel = client.cancelOrder(resp.orderId()).join();
                System.out.println("cancel -> canceled=" + cancel.canceled());
            }
        }
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
        if ("AMOY".equalsIgnoreCase(raw)) {
            return ChainId.AMOY;
        }
        return Long.parseLong(raw);
    }

    private static SignatureType parseSignatureType(String raw) {
        if (raw == null || raw.isBlank()) {
            return SignatureType.POLY_PROXY;
        }
        return SignatureType.valueOf(raw.trim().toUpperCase());
    }
}
