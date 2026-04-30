package com.polymarket.clob.api;

import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.order.CancelMarketOrdersRequest;
import com.polymarket.clob.order.CancelResponse;
import com.polymarket.clob.order.OpenOrder;
import com.polymarket.clob.order.OpenOrderParams;
import com.polymarket.clob.order.OrderScoringResponse;
import com.polymarket.clob.order.OrderType;
import com.polymarket.clob.order.OrdersScoringResponse;
import com.polymarket.clob.order.PostOrderResponse;
import com.polymarket.clob.order.PostOrdersEntry;
import com.polymarket.clob.order.SignedOrder;
import com.polymarket.clob.order.SignedOrderV2;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * CLOB 订单交易接口，覆盖 Plan 3 · Trading 的核心能力。所有方法均需 L2 凭证。
 *
 * <p>签名与传输边界说明：
 * <ul>
 *   <li>{@code caller} 是 {@code POLY_ADDRESS} 头的 EOA（签名 API Key 的 funder）。</li>
 *   <li>{@code timestamp} 由调用方注入，单个分页/批量请求内复用，避免页间签名漂移。</li>
 *   <li>Body 序列化由实现内部完成并用同一份 bytes 计算 L2 签名，确保"签的就是发的"。</li>
 * </ul>
 * </p>
 */
public interface OrderApi {

    /** {@code POST /order}：单笔下单。 */
    CompletableFuture<PostOrderResponse> postOrder(
            Address caller,
            ApiCredentials credentials,
            long timestamp,
            SignedOrder order,
            OrderType orderType,
            boolean postOnly);

    /** 便利重载：{@code postOnly=false}，适合 FOK/FAK。 */
    default CompletableFuture<PostOrderResponse> postOrder(
            Address caller, ApiCredentials credentials, long timestamp,
            SignedOrder order, OrderType orderType) {
        return postOrder(caller, credentials, timestamp, order, orderType, false);
    }

    /**
     * {@code POST /order}（V2）：单笔下 V2 订单。
     *
     * <p>与 V1 {@link #postOrder(Address, ApiCredentials, long, SignedOrder, OrderType, boolean)}
     * 的 wire 区别：
     * <ul>
     *   <li>order 内含 V2 字段（{@code timestamp / metadata / builder}），不含 V1 字段
     *       （{@code taker / nonce / feeRateBps}）；</li>
     *   <li>顶层多一个 {@code deferExec} 字段；</li>
     *   <li>字段顺序刻意对齐 py-clob-client-v2 {@code order_to_json_v2}，便于 parity 比对。</li>
     * </ul>
     * </p>
     *
     * @param deferExec 是否延后撮合执行；非 RFQ 场景一般传 {@code false}
     */
    CompletableFuture<PostOrderResponse> postOrderV2(
            Address caller,
            ApiCredentials credentials,
            long timestamp,
            SignedOrderV2 order,
            OrderType orderType,
            boolean postOnly,
            boolean deferExec);

    /** V2 便利重载：{@code postOnly=false / deferExec=false}。 */
    default CompletableFuture<PostOrderResponse> postOrderV2(
            Address caller, ApiCredentials credentials, long timestamp,
            SignedOrderV2 order, OrderType orderType) {
        return postOrderV2(caller, credentials, timestamp, order, orderType, false, false);
    }

    /**
     * {@code POST /orders}：批量下单。上游限制 15 条/批；调用方负责切分。
     * 实现会拒绝 {@code entries.isEmpty()} 或 {@code size > 15}。
     */
    CompletableFuture<PostOrderResponse> postOrders(
            Address caller,
            ApiCredentials credentials,
            long timestamp,
            List<PostOrdersEntry> entries);

    /** {@code DELETE /order}：按 id 取消单笔。 */
    CompletableFuture<CancelResponse> cancelOrder(
            Address caller,
            ApiCredentials credentials,
            long timestamp,
            String orderId);

    /** {@code DELETE /orders}：批量取消。 */
    CompletableFuture<CancelResponse> cancelOrders(
            Address caller,
            ApiCredentials credentials,
            long timestamp,
            List<String> orderIds);

    /** {@code DELETE /cancel-all}：取消调用者全部开放订单。 */
    CompletableFuture<CancelResponse> cancelAll(
            Address caller,
            ApiCredentials credentials,
            long timestamp);

    /**
     * {@code DELETE /cancel-market-orders}：按 market / asset_id 取消一组订单。
     * 请求体是 {@code {market, asset_id}} JSON；参与 HMAC 签名。
     */
    CompletableFuture<CancelResponse> cancelMarketOrders(
            Address caller,
            ApiCredentials credentials,
            long timestamp,
            CancelMarketOrdersRequest request);

    /**
     * {@code GET /order-scoring}：查询单笔订单当前是否满足做市商奖励资格。
     * 签名路径包含 query（{@code ?order_id=...}）会漂移，故按 py-clob-client 约定签名路径只含 {@code /order-scoring}，
     * HTTP 发送时 query 仅作为过滤器，不再参与 HMAC。
     */
    CompletableFuture<OrderScoringResponse> isOrderScoring(
            Address caller,
            ApiCredentials credentials,
            long timestamp,
            String orderId);

    /**
     * {@code POST /orders-scoring}：批量查询多笔订单的奖励资格。
     * 请求体是 {@code ["id1", "id2", ...]} JSON 字符串数组。
     */
    CompletableFuture<OrdersScoringResponse> areOrdersScoring(
            Address caller,
            ApiCredentials credentials,
            long timestamp,
            List<String> orderIds);

    /** {@code GET /data/order/{id}}：按 id 查询单笔订单。 */
    CompletableFuture<OpenOrder> getOrder(
            Address caller,
            ApiCredentials credentials,
            long timestamp,
            String orderId);

    /**
     * {@code GET /data/orders}：查询调用者开放订单。返回惰性 {@link Stream} 聚合所有分页；
     * 内部共用一组 L2 头，与 py-clob-client 语义一致。
     */
    Stream<OpenOrder> getOpenOrders(
            Address caller,
            ApiCredentials credentials,
            long timestamp,
            OpenOrderParams params);
}
