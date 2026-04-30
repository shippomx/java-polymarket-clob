package com.polymarket.clob.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.auth.L2HeaderBuilder;
import com.polymarket.clob.exception.ClobSerializationException;
import com.polymarket.clob.http.CursorPager;
import com.polymarket.clob.http.HttpTransport;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * {@link OrderApi} 的默认实现。
 *
 * <p>关键不变量：
 * <ul>
 *   <li>所有带 body 的端点先走 {@link HttpTransport#objectMapper()} 序列化，再把同一份字符串
 *       同时喂给 {@link L2HeaderBuilder} 与 {@link HttpTransport#postRaw}/{@link
 *       HttpTransport#deleteRaw}。避免 Jackson 两次调用产生的不可见漂移破坏 HMAC 校验。</li>
 *   <li>{@code POST /order} body 形态：{@code {order:{...signed order + signature...},
 *       owner:apiKey, orderType:"GTC", postOnly:false}}，与 py-clob-client 的
 *       {@code order_to_json(order, api_key, orderType, post_only)} 字段一致。</li>
 *   <li>{@code POST /orders} body 是上面对象的 JSON 数组。</li>
 *   <li>{@code DELETE /order} body = {@code {"orderID":"<id>"}}；{@code DELETE /orders} body
 *       是 id 字符串数组；{@code DELETE /cancel-all} 无 body（null）。</li>
 *   <li>{@code GET /data/order/{id}} L2 签名路径 <b>包含 id</b>；{@code GET /data/orders}
 *       路径不含 query，分页所有请求复用同一组签名（与 py-clob-client 一致）。</li>
 * </ul>
 * </p>
 */
public final class OrderApiImpl implements OrderApi {

    private static final String POST_ORDER_PATH = "/order";
    private static final String POST_ORDERS_PATH = "/orders";
    private static final String CANCEL_ALL_PATH = "/cancel-all";
    private static final String CANCEL_MARKET_ORDERS_PATH = "/cancel-market-orders";
    private static final String DATA_ORDER_PATH = "/data/order/";
    private static final String DATA_ORDERS_PATH = "/data/orders";
    private static final String ORDER_SCORING_PATH = "/order-scoring";
    private static final String ORDERS_SCORING_PATH = "/orders-scoring";

    private final HttpTransport transport;
    private final ObjectMapper mapper;

    public OrderApiImpl(HttpTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.mapper = transport.objectMapper();
    }

    // ------------------------------------------------------------------ POST

    @Override
    public CompletableFuture<PostOrderResponse> postOrder(Address caller,
                                                          ApiCredentials credentials,
                                                          long timestamp,
                                                          SignedOrder order,
                                                          OrderType orderType,
                                                          boolean postOnly) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(orderType, "orderType");
        if (postOnly && !orderType.isLimit()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "postOnly requires limit order (GTC/GTD), got " + orderType));
        }
        PostOrderBody body = new PostOrderBody(order, credentials.apiKey(), orderType, postOnly);
        return postSigned(POST_ORDER_PATH, "order", body, caller, credentials, timestamp,
                new TypeReference<PostOrderResponse>() {});
    }

    @Override
    public CompletableFuture<PostOrderResponse> postOrderV2(Address caller,
                                                            ApiCredentials credentials,
                                                            long timestamp,
                                                            SignedOrderV2 order,
                                                            OrderType orderType,
                                                            boolean postOnly,
                                                            boolean deferExec) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(orderType, "orderType");
        if (postOnly && !orderType.isLimit()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "postOnly requires limit order (GTC/GTD), got " + orderType));
        }
        PostOrderBodyV2 body = new PostOrderBodyV2(order, credentials.apiKey(), orderType, deferExec, postOnly);
        return postSigned(POST_ORDER_PATH, "order", body, caller, credentials, timestamp,
                new TypeReference<PostOrderResponse>() {});
    }

    @Override
    public CompletableFuture<PostOrderResponse> postOrders(Address caller,
                                                           ApiCredentials credentials,
                                                           long timestamp,
                                                           List<PostOrdersEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        if (entries.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "batch postOrders requires at least one entry"));
        }
        if (entries.size() > 15) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "batch postOrders upper limit is 15, got " + entries.size()));
        }
        List<PostOrderBody> body = new ArrayList<>(entries.size());
        for (PostOrdersEntry e : entries) {
            body.add(new PostOrderBody(e.order(), credentials.apiKey(), e.orderType(), e.postOnly()));
        }
        return postSigned(POST_ORDERS_PATH, "orders", body, caller, credentials, timestamp,
                new TypeReference<PostOrderResponse>() {});
    }

    // ------------------------------------------------------------------ DELETE

    @Override
    public CompletableFuture<CancelResponse> cancelOrder(Address caller,
                                                         ApiCredentials credentials,
                                                         long timestamp,
                                                         String orderId) {
        Objects.requireNonNull(orderId, "orderId");
        CancelOrderBody body = new CancelOrderBody(orderId);
        return deleteSigned(POST_ORDER_PATH, "order", body, caller, credentials, timestamp,
                new TypeReference<CancelResponse>() {});
    }

    @Override
    public CompletableFuture<CancelResponse> cancelOrders(Address caller,
                                                          ApiCredentials credentials,
                                                          long timestamp,
                                                          List<String> orderIds) {
        Objects.requireNonNull(orderIds, "orderIds");
        if (orderIds.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "cancelOrders requires at least one id"));
        }
        return deleteSigned(POST_ORDERS_PATH, "orders", orderIds, caller, credentials, timestamp,
                new TypeReference<CancelResponse>() {});
    }

    @Override
    public CompletableFuture<CancelResponse> cancelAll(Address caller,
                                                       ApiCredentials credentials,
                                                       long timestamp) {
        // null body → deleteRaw 会跳过 Content-Type，与 py-clob-client 行为一致
        Map<String, String> headers = L2HeaderBuilder.build(
                caller, credentials, "DELETE", CANCEL_ALL_PATH, "", timestamp);
        return transport.deleteRaw("cancel-all", null, headers,
                new TypeReference<CancelResponse>() {});
    }

    @Override
    public CompletableFuture<CancelResponse> cancelMarketOrders(Address caller,
                                                                ApiCredentials credentials,
                                                                long timestamp,
                                                                CancelMarketOrdersRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.market() == null && request.assetId() == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "cancelMarketOrders requires at least one of market / assetId"));
        }
        return deleteSigned(CANCEL_MARKET_ORDERS_PATH, "cancel-market-orders", request,
                caller, credentials, timestamp,
                new TypeReference<CancelResponse>() {});
    }

    // ------------------------------------------------------------------ GET

    @Override
    public CompletableFuture<OpenOrder> getOrder(Address caller,
                                                 ApiCredentials credentials,
                                                 long timestamp,
                                                 String orderId) {
        Objects.requireNonNull(orderId, "orderId");
        String path = DATA_ORDER_PATH + orderId;
        Map<String, String> headers = L2HeaderBuilder.build(
                caller, credentials, "GET", path, "", timestamp);
        // 相对 path 去掉前导 '/'；pathSegment 做 URL 编码以兼容特殊字符
        String relative = "data/order/" + HttpTransport.pathSegment(orderId);
        return transport.get(relative, Map.of(), headers, new TypeReference<OpenOrder>() {});
    }

    @Override
    public Stream<OpenOrder> getOpenOrders(Address caller,
                                           ApiCredentials credentials,
                                           long timestamp,
                                           OpenOrderParams params) {
        Map<String, String> headers = L2HeaderBuilder.build(
                caller, credentials, "GET", DATA_ORDERS_PATH, "", timestamp);
        Map<String, ?> baseQuery = params == null ? Map.of() : params.toQueryParams();
        return CursorPager.stream(
                transport,
                "data/orders",
                baseQuery,
                headers,
                new TypeReference<CursorPager.Page<OpenOrder>>() {});
    }

    @Override
    public CompletableFuture<OrderScoringResponse> isOrderScoring(Address caller,
                                                                  ApiCredentials credentials,
                                                                  long timestamp,
                                                                  String orderId) {
        Objects.requireNonNull(orderId, "orderId");
        // 与 py/rs-clob-client 一致：HMAC 只包含 path，不含 query，避免 URL 编码差异破坏签名
        Map<String, String> headers = L2HeaderBuilder.build(
                caller, credentials, "GET", ORDER_SCORING_PATH, "", timestamp);
        return transport.get("order-scoring", Map.of("order_id", orderId), headers,
                new TypeReference<OrderScoringResponse>() {});
    }

    @Override
    public CompletableFuture<OrdersScoringResponse> areOrdersScoring(Address caller,
                                                                     ApiCredentials credentials,
                                                                     long timestamp,
                                                                     List<String> orderIds) {
        Objects.requireNonNull(orderIds, "orderIds");
        if (orderIds.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "areOrdersScoring requires at least one orderId"));
        }
        return postSigned(ORDERS_SCORING_PATH, "orders-scoring", orderIds,
                caller, credentials, timestamp,
                new TypeReference<OrdersScoringResponse>() {});
    }

    // ------------------------------------------------------------------ internal

    private <R> CompletableFuture<R> postSigned(String path,
                                                String relative,
                                                Object body,
                                                Address caller,
                                                ApiCredentials credentials,
                                                long timestamp,
                                                TypeReference<R> responseType) {
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(
                    new ClobSerializationException("Failed to serialize body for POST " + path, e));
        }
        Map<String, String> headers = L2HeaderBuilder.build(
                caller, credentials, "POST", path, json, timestamp);
        return transport.postRaw(relative, json, headers, responseType);
    }

    private <R> CompletableFuture<R> deleteSigned(String path,
                                                  String relative,
                                                  Object body,
                                                  Address caller,
                                                  ApiCredentials credentials,
                                                  long timestamp,
                                                  TypeReference<R> responseType) {
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(
                    new ClobSerializationException("Failed to serialize body for DELETE " + path, e));
        }
        Map<String, String> headers = L2HeaderBuilder.build(
                caller, credentials, "DELETE", path, json, timestamp);
        return transport.deleteRaw(relative, json, headers, responseType);
    }

    /**
     * {@code POST /order} / {@code POST /orders} 的外层 wrapper。
     * 字段顺序刻意对齐 py-clob-client {@code order_to_json} 输出。
     */
    static final class PostOrderBody {
        @JsonProperty("order") final SignedOrder order;
        @JsonProperty("owner") final String owner;
        @JsonProperty("orderType") final OrderType orderType;
        @JsonProperty("postOnly") final boolean postOnly;

        PostOrderBody(SignedOrder order, String owner, OrderType orderType, boolean postOnly) {
            this.order = order;
            this.owner = owner;
            this.orderType = orderType;
            this.postOnly = postOnly;
        }

        public SignedOrder getOrder() { return order; }
        public String getOwner() { return owner; }
        public OrderType getOrderType() { return orderType; }
        public boolean isPostOnly() { return postOnly; }
    }

    /** {@code DELETE /order} 的 {@code {"orderID":"<id>"}} body。 */
    static final class CancelOrderBody {
        @JsonProperty("orderID") final String orderId;

        CancelOrderBody(String orderId) { this.orderId = orderId; }

        public String getOrderID() { return orderId; }
    }

    /**
     * V2 {@code POST /order} 的外层 wrapper。字段顺序刻意对齐 py-clob-client-v2
     * {@code order_to_json_v2} 输出：{@code order / owner / orderType / deferExec / postOnly}。
     */
    static final class PostOrderBodyV2 {
        @JsonProperty("order") final SignedOrderV2 order;
        @JsonProperty("owner") final String owner;
        @JsonProperty("orderType") final OrderType orderType;
        @JsonProperty("deferExec") final boolean deferExec;
        @JsonProperty("postOnly") final boolean postOnly;

        PostOrderBodyV2(SignedOrderV2 order, String owner, OrderType orderType,
                        boolean deferExec, boolean postOnly) {
            this.order = order;
            this.owner = owner;
            this.orderType = orderType;
            this.deferExec = deferExec;
            this.postOnly = postOnly;
        }

        public SignedOrderV2 getOrder() { return order; }
        public String getOwner() { return owner; }
        public OrderType getOrderType() { return orderType; }
        public boolean isDeferExec() { return deferExec; }
        public boolean isPostOnly() { return postOnly; }
    }
}
