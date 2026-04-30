package com.polymarket.clob;

import com.polymarket.clob.api.AccountApi;
import com.polymarket.clob.api.AuthApi;
import com.polymarket.clob.api.BuilderApi;
import com.polymarket.clob.api.HeartbeatApi;
import com.polymarket.clob.api.MarketDataApi;
import com.polymarket.clob.api.OrderApi;
import com.polymarket.clob.api.TradeApi;
import com.polymarket.clob.api.model.BuilderApiKeyResponse;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.auth.builder.BuilderConfig;
import com.polymarket.clob.auth.builder.BuilderHeaderBuilder;
import com.polymarket.clob.heartbeat.HeartbeatResponse;
import com.polymarket.clob.heartbeat.HeartbeatScheduler;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.BalanceAllowanceRequest;
import com.polymarket.clob.model.BalanceAllowanceResponse;
import com.polymarket.clob.model.BanStatusResponse;
import com.polymarket.clob.order.CancelMarketOrdersRequest;
import com.polymarket.clob.order.CancelResponse;
import com.polymarket.clob.order.CreateOrderOptions;
import com.polymarket.clob.order.LimitOrderArgs;
import com.polymarket.clob.order.MarketOrderArgs;
import com.polymarket.clob.order.OpenOrder;
import com.polymarket.clob.order.OpenOrderParams;
import com.polymarket.clob.order.OrderBuilder;
import com.polymarket.clob.order.OrderScoringResponse;
import com.polymarket.clob.order.OrderType;
import com.polymarket.clob.order.OrdersScoringResponse;
import com.polymarket.clob.order.PostOrderResponse;
import com.polymarket.clob.order.PostOrdersEntry;
import com.polymarket.clob.order.LimitOrderArgsV2;
import com.polymarket.clob.order.MarketOrderArgsV2;
import com.polymarket.clob.order.SignedOrder;
import com.polymarket.clob.order.SignedOrderV2;
import com.polymarket.clob.trade.BuilderTrade;
import com.polymarket.clob.trade.Trade;
import com.polymarket.clob.trade.TradesRequest;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Builder 认证态 CLOB 客户端（typestate 最顶层）。
 *
 * <p>与 {@link AuthenticatedClobClient} 的关系：
 * <ul>
 *   <li>组合而非继承：内部委托一个 {@link AuthenticatedClobClient}，所有 L2 能力照常透出；</li>
 *   <li>额外暴露 Builder 专属端点：{@code /builder/trades}、{@code /auth/builder-api-key} 的
 *       列表/吊销，均自动叠加 {@code POLY_BUILDER_*} 四联头；</li>
 *   <li>共享 {@link HttpTransport}、{@link com.polymarket.clob.auth.Signer}、{@link ApiCredentials}；
 *       升级路径 = 附加 {@link BuilderConfig}，不会吊销原有 L2 凭证。</li>
 * </ul>
 *
 * <p>线程安全：所有字段 {@code final}，{@link BuilderHeaderBuilder} 内部无可变状态（Remote
 * 模式下复用 {@link java.net.http.HttpClient}）。</p>
 *
 * <p>对齐 Rust {@code BuilderClobClient}：Rust 版本通过 {@code ClobClient::promote_to_builder}
 * 消费 self；Java 因为没有所有权语义，这里让两个 client 并存——用户若想"防误用"，可在升级后
 * 丢弃原 {@link AuthenticatedClobClient} 引用。</p>
 */
@Accessors(fluent = true)
public final class BuilderClobClient implements AutoCloseable {

    @Getter private final BuilderConfig builderConfig;
    private final AuthenticatedClobClient delegate;
    private final BuilderHeaderBuilder headerBuilder;
    private final Clock clock;

    BuilderClobClient(AuthenticatedClobClient delegate, BuilderConfig config) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.builderConfig = Objects.requireNonNull(config, "config");
        this.headerBuilder = new BuilderHeaderBuilder(config);
        this.clock = delegate.clockInternal();
    }

    // ---------------- typestate 透出：只读访问器，全部委托给内部 AuthenticatedClobClient ----------------

    /** 底层已认证客户端。便于调用方在 builder/普通模式之间快速切回。 */
    public AuthenticatedClobClient authenticated() { return delegate; }

    public URI endpoint() { return delegate.endpoint(); }
    public long chainId() { return delegate.chainId(); }
    public Address funder() { return delegate.funder(); }
    public SignatureType signatureType() { return delegate.signatureType(); }
    public MarketDataApi market() { return delegate.market(); }
    public AuthApi auth() { return delegate.auth(); }
    public AccountApi account() { return delegate.account(); }
    public OrderApi order() { return delegate.order(); }
    public TradeApi trade() { return delegate.trade(); }
    public HeartbeatApi heartbeat() { return delegate.heartbeat(); }
    public BuilderApi builder() { return delegate.builder(); }
    public OrderBuilder orderBuilder() { return delegate.orderBuilder(); }
    public Address callerAddress() { return delegate.callerAddress(); }
    public String apiKeyId() { return delegate.apiKeyId(); }

    // ---------------- 透传：下单、取消、查询 ----------------

    public CompletableFuture<PostOrderResponse> createAndPostLimitOrder(
            LimitOrderArgs args, CreateOrderOptions options, OrderType orderType, boolean postOnly) {
        return delegate.createAndPostLimitOrder(args, options, orderType, postOnly);
    }

    public CompletableFuture<PostOrderResponse> createAndPostMarketOrder(
            MarketOrderArgs args, CreateOrderOptions options, OrderType orderType) {
        return delegate.createAndPostMarketOrder(args, options, orderType);
    }

    public CompletableFuture<PostOrderResponse> postOrder(
            SignedOrder signed, OrderType orderType, boolean postOnly) {
        return delegate.postOrder(signed, orderType, postOnly);
    }

    public CompletableFuture<PostOrderResponse> postOrders(List<PostOrdersEntry> entries) {
        return delegate.postOrders(entries);
    }

    // ---------------- V2 透传 ----------------

    public CompletableFuture<PostOrderResponse> createAndPostLimitOrderV2(
            LimitOrderArgsV2 args, CreateOrderOptions options, OrderType orderType, boolean postOnly) {
        return delegate.createAndPostLimitOrderV2(args, options, orderType, postOnly);
    }

    public CompletableFuture<PostOrderResponse> createAndPostMarketOrderV2(
            MarketOrderArgsV2 args, CreateOrderOptions options, OrderType orderType) {
        return delegate.createAndPostMarketOrderV2(args, options, orderType);
    }

    public CompletableFuture<PostOrderResponse> postOrderV2(
            SignedOrderV2 signed, OrderType orderType, boolean postOnly) {
        return delegate.postOrderV2(signed, orderType, postOnly);
    }

    public CompletableFuture<PostOrderResponse> postOrderV2(
            SignedOrderV2 signed, OrderType orderType, boolean postOnly, boolean deferExec) {
        return delegate.postOrderV2(signed, orderType, postOnly, deferExec);
    }

    public CompletableFuture<CancelResponse> cancelOrder(String orderId) {
        return delegate.cancelOrder(orderId);
    }

    public CompletableFuture<CancelResponse> cancelOrders(List<String> orderIds) {
        return delegate.cancelOrders(orderIds);
    }

    public CompletableFuture<CancelResponse> cancelAll() {
        return delegate.cancelAll();
    }

    public CompletableFuture<CancelResponse> cancelMarketOrders(CancelMarketOrdersRequest request) {
        return delegate.cancelMarketOrders(request);
    }

    public CompletableFuture<OpenOrder> getOrder(String orderId) {
        return delegate.getOrder(orderId);
    }

    public Stream<OpenOrder> getOpenOrders(OpenOrderParams params) {
        return delegate.getOpenOrders(params);
    }

    public CompletableFuture<OrderScoringResponse> isOrderScoring(String orderId) {
        return delegate.isOrderScoring(orderId);
    }

    public CompletableFuture<OrdersScoringResponse> areOrdersScoring(List<String> orderIds) {
        return delegate.areOrdersScoring(orderIds);
    }

    public Stream<Trade> getTrades(TradesRequest request) {
        return delegate.getTrades(request);
    }

    public CompletableFuture<BalanceAllowanceResponse> balanceAllowance(BalanceAllowanceRequest request) {
        return delegate.balanceAllowance(request);
    }

    public CompletableFuture<BanStatusResponse> closedOnlyMode() {
        return delegate.closedOnlyMode();
    }

    public CompletableFuture<HeartbeatResponse> postHeartbeat(UUID heartbeatId) {
        return delegate.postHeartbeat(heartbeatId);
    }

    public HeartbeatScheduler startHeartbeats() { return delegate.startHeartbeats(); }

    public HeartbeatScheduler startHeartbeats(Duration interval) {
        return delegate.startHeartbeats(interval);
    }

    // ---------------- Builder 专属：自动合成 POLY_BUILDER_* 头 ----------------

    /**
     * {@code GET /builder/trades}：Builder 视角的交易历史。
     *
     * <p>签名路径：
     * <ol>
     *   <li>生成 L2 头（timestamp 当前秒）；</li>
     *   <li>用同一个 timestamp/path/method 生成 {@code POLY_BUILDER_*}；</li>
     *   <li>合并后交给 {@link TradeApi#getBuilderTrades}；</li>
     *   <li>分页期间头部是静态复用的——与 py-clob-client / rs-clob-client 保持一致。</li>
     * </ol>
     *
     * <p>Remote builder 模式下会阻塞一次远程签名，失败以 {@link com.polymarket.clob.exception.ClobAuthException}
     * 形式传播。因此本方法返回 {@code Stream}，调用方迭代时首个元素可能慢若干百 ms。</p>
     */
    public Stream<BuilderTrade> getBuilderTrades(TradesRequest request) {
        long timestamp = now();
        Map<String, String> builderHeaders = buildBuilderHeadersSync(
                "GET", "/builder/trades", "", timestamp);
        return delegate.tradeInternal().getBuilderTrades(
                callerAddress(), delegate.credentials(), timestamp, request, builderHeaders);
    }

    /**
     * {@code GET /auth/builder-api-key}：列出当前 Builder 拥有的 API Keys。
     * 需要 {@code POLY_BUILDER_*} 头——非 Builder 态调用会被服务端拒绝。
     */
    public CompletableFuture<List<BuilderApiKeyResponse>> listBuilderApiKeys() {
        long timestamp = now();
        return buildBuilderHeaders("GET", "/auth/builder-api-key", "", timestamp)
                .thenCompose(extra -> delegate.builderInternal().builderApiKeys(
                        callerAddress(), delegate.credentials(), timestamp, extra));
    }

    /**
     * {@code DELETE /auth/builder-api-key}：吊销当前 Builder API Key。
     *
     * <p>注意：服务端会标记 {@code revoked_at} 而非物理删除；吊销后此客户端再调用 builder 专属端点
     * 仍会使用同一把已失效凭证，应当由调用方主动重建 {@link BuilderClobClient}。</p>
     */
    public CompletableFuture<Void> revokeBuilderApiKey() {
        long timestamp = now();
        return buildBuilderHeaders("DELETE", "/auth/builder-api-key", "", timestamp)
                .thenCompose(extra -> delegate.builderInternal().revokeBuilderApiKey(
                        callerAddress(), delegate.credentials(), timestamp, extra));
    }

    /**
     * {@code POST /auth/builder-api-key}：申请新的 Builder API Key。
     *
     * <p>与 {@link #listBuilderApiKeys()} / {@link #revokeBuilderApiKey()} 不同：
     * create 不需要 {@code POLY_BUILDER_*} 头（发起人尚未有 Builder 凭证），走普通 L2。
     * 返回的新凭证由调用方自行保存，或基于它重建一个新的 {@link BuilderClobClient}。</p>
     */
    public CompletableFuture<ApiCredentials> createBuilderApiKey() {
        return delegate.builderInternal().createBuilderApiKey(
                callerAddress(), delegate.credentials(), now());
    }

    /** 暴露底层 {@link BuilderHeaderBuilder}，高级调用方可自行构造带 Builder 头的请求。 */
    public BuilderHeaderBuilder headerBuilder() {
        return headerBuilder;
    }

    // ---------------- 内部 ----------------

    private CompletableFuture<Map<String, String>> buildBuilderHeaders(
            String method, String path, String body, long timestamp) {
        return headerBuilder.build(method, path, body, timestamp);
    }

    /**
     * 同步版：仅用于 Stream 返回入口（{@link #getBuilderTrades}）。
     * Local 模式下这其实就是 immediate future；Remote 模式会阻塞等待远程 signing。
     */
    private Map<String, String> buildBuilderHeadersSync(
            String method, String path, String body, long timestamp) {
        return headerBuilder.build(method, path, body, timestamp).join();
    }

    private long now() {
        return clock.instant().getEpochSecond();
    }

    /** 委托给底层 {@link AuthenticatedClobClient#close()}；当前为 no-op。 */
    @Override
    public void close() {
        delegate.close();
    }
}
