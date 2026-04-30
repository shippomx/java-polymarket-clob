package com.polymarket.clob;

import com.polymarket.clob.api.AccountApi;
import com.polymarket.clob.api.AccountApiImpl;
import com.polymarket.clob.api.AuthApi;
import com.polymarket.clob.api.AuthApiImpl;
import com.polymarket.clob.api.BuilderApi;
import com.polymarket.clob.api.BuilderApiImpl;
import com.polymarket.clob.api.HeartbeatApi;
import com.polymarket.clob.api.HeartbeatApiImpl;
import com.polymarket.clob.api.MarketDataApi;
import com.polymarket.clob.api.OrderApi;
import com.polymarket.clob.api.OrderApiImpl;
import com.polymarket.clob.api.TradeApi;
import com.polymarket.clob.api.TradeApiImpl;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.auth.builder.BuilderConfig;
import com.polymarket.clob.heartbeat.HeartbeatResponse;
import com.polymarket.clob.heartbeat.HeartbeatScheduler;
import com.polymarket.clob.http.HttpTransport;
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
import com.polymarket.clob.order.SaltSource;
import com.polymarket.clob.order.LimitOrderArgsV2;
import com.polymarket.clob.order.MarketOrderArgsV2;
import com.polymarket.clob.order.SignedOrder;
import com.polymarket.clob.order.SignedOrderV2;
import com.polymarket.clob.trade.Trade;
import com.polymarket.clob.trade.TradesRequest;
import com.polymarket.clob.ws.AuthenticatedClobWebSocketClient;
import com.polymarket.clob.ws.WebSocketConfig;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * 已认证的 CLOB 客户端（typestate 升级态）。
 *
 * <p>与 {@link ClobClient} 不共享继承关系：编译期即可区分需/不需凭证的调用，无法"降级误用"。
 * 经由 {@link ClobClient#authenticate(Signer, SignatureType, ApiCredentials)} 或其兄弟重载
 * 进入此状态。</p>
 *
 * <p>本类线程安全：所有字段 {@code final}；每次需要 L2/L1 签名时用构造时传入的
 * {@link Signer} 产出最新 timestamp；凭证即持久态。</p>
 */
@Accessors(fluent = true)
public final class AuthenticatedClobClient implements AutoCloseable {

    @Getter private final URI endpoint;
    @Getter private final long chainId;
    @Getter private final Address funder;
    @Getter private final SignatureType signatureType;

    private final HttpTransport transport;
    private final MarketDataApi market;
    private final AuthApi auth;
    private final AccountApi account;
    private final OrderApi order;
    private final TradeApi trade;
    private final HeartbeatApi heartbeat;
    private final BuilderApi builder;
    private final OrderBuilder orderBuilder;
    private final Signer signer;
    private final ApiCredentials credentials;
    private final Clock clock;
    private final SaltSource saltSource;

    /**
     * 旧 8 参构造器：默认 {@link Clock#systemUTC()} + {@link SaltSource#secureRandom()}。
     * 保留以避免影响现有 caller；新调用请走 10 参版本。
     */
    AuthenticatedClobClient(
            URI endpoint,
            long chainId,
            HttpTransport transport,
            MarketDataApi market,
            Signer signer,
            SignatureType signatureType,
            Address funder,
            ApiCredentials credentials) {
        this(endpoint, chainId, transport, market, signer, signatureType, funder, credentials,
                Clock.systemUTC(), SaltSource.secureRandom());
    }

    /**
     * 完整构造器：允许注入 {@link Clock} 与 {@link SaltSource}，
     * 用于 parity 测试中固定 timestamp / salt 产出可重现 golden 向量。
     * {@code null} 会回落到默认 systemUTC / secureRandom。
     */
    AuthenticatedClobClient(
            URI endpoint,
            long chainId,
            HttpTransport transport,
            MarketDataApi market,
            Signer signer,
            SignatureType signatureType,
            Address funder,
            ApiCredentials credentials,
            Clock clock,
            SaltSource saltSource) {
        this.endpoint = endpoint;
        this.chainId = chainId;
        this.transport = transport;
        this.market = market;
        this.signer = Objects.requireNonNull(signer, "signer");
        this.signatureType = Objects.requireNonNull(signatureType, "signatureType");
        this.funder = Objects.requireNonNull(funder, "funder");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.auth = new AuthApiImpl(transport, chainId);
        this.account = new AccountApiImpl(transport);
        this.order = new OrderApiImpl(transport);
        this.trade = new TradeApiImpl(transport);
        this.heartbeat = new HeartbeatApiImpl(transport);
        this.builder = new BuilderApiImpl(transport);
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.saltSource = saltSource == null ? SaltSource.secureRandom() : saltSource;
        this.orderBuilder = new OrderBuilder(
                chainId, signer, funder, signatureType, this.saltSource);
    }

    /** 只读市场数据 API（与 {@link ClobClient#market()} 同义）。 */
    public MarketDataApi market() { return market; }

    /** L1/L2 API Key 管理接口。 */
    public AuthApi auth() { return auth; }

    /** 账户资金查询接口。 */
    public AccountApi account() { return account; }

    /** 订单交易接口（post/cancel/query）。 */
    public OrderApi order() { return order; }

    /** 交易历史查询接口（对应 {@code /data/trades}）。 */
    public TradeApi trade() { return trade; }

    /** 心跳接口（对应 {@code /v1/heartbeats}）。 */
    public HeartbeatApi heartbeat() { return heartbeat; }

    /** Builder 密钥生命周期接口（对应 {@code /auth/builder-api-key}）。 */
    public BuilderApi builder() { return builder; }

    /**
     * 构造 + 签名订单的便利入口。共享 {@link Signer}/funder/chainId/signatureType，
     * 外部无需重复传参。多线程复用安全——{@link OrderBuilder} 无可变状态。
     */
    public OrderBuilder orderBuilder() { return orderBuilder; }

    // ---------------- 便利方法：create → sign → post 一条龙 ----------------

    /**
     * 限价单：构造 → EIP-712 签名 → {@code POST /order}。
     * 内部 timestamp 自动取当前 Unix 秒。
     */
    public CompletableFuture<PostOrderResponse> createAndPostLimitOrder(
            LimitOrderArgs args, CreateOrderOptions options, OrderType orderType, boolean postOnly) {
        Objects.requireNonNull(orderType, "orderType");
        if (orderType.isMarket()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "createAndPostLimitOrder requires a limit order type (GTC/GTD), got " + orderType));
        }
        return orderBuilder.createOrder(args, options)
                .thenCompose(signed -> order.postOrder(
                        signer.address(), credentials, now(), signed, orderType, postOnly));
    }

    /** 市价单（FOK 默认）：构造 → 签名 → post。 */
    public CompletableFuture<PostOrderResponse> createAndPostMarketOrder(
            MarketOrderArgs args, CreateOrderOptions options, OrderType orderType) {
        Objects.requireNonNull(orderType, "orderType");
        if (!orderType.isMarket()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "createAndPostMarketOrder requires FOK/FAK, got " + orderType));
        }
        return orderBuilder.createMarketOrder(args, options)
                .thenCompose(signed -> order.postOrder(
                        signer.address(), credentials, now(), signed, orderType, false));
    }

    /** {@code POST /order}：直接提交已签名订单。 */
    public CompletableFuture<PostOrderResponse> postOrder(
            SignedOrder signed, OrderType orderType, boolean postOnly) {
        return order.postOrder(signer.address(), credentials, now(), signed, orderType, postOnly);
    }

    /** 批量 {@code POST /orders}。上限 15 条。 */
    public CompletableFuture<PostOrderResponse> postOrders(List<PostOrdersEntry> entries) {
        return order.postOrders(signer.address(), credentials, now(), entries);
    }

    // ---------------- V2 路径（CTF Exchange v2，2026-04-28 上线后） ----------------

    /**
     * V2 限价单：构造 → V2 EIP-712 签名 → {@code POST /order}（V2 wire）。
     *
     * <p>调用方需保证 {@link OrderBuilder} 的 chainId 已注册 V2 部署
     * （{@link com.polymarket.clob.model.ContractRegistry#exchangeV2}），否则签名阶段抛
     * {@link com.polymarket.clob.exception.ClobSignatureException}。</p>
     */
    public CompletableFuture<PostOrderResponse> createAndPostLimitOrderV2(
            LimitOrderArgsV2 args, CreateOrderOptions options, OrderType orderType, boolean postOnly) {
        Objects.requireNonNull(orderType, "orderType");
        if (orderType.isMarket()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "createAndPostLimitOrderV2 requires a limit order type (GTC/GTD), got " + orderType));
        }
        return orderBuilder.createOrderV2(args, options)
                .thenCompose(signed -> order.postOrderV2(
                        signer.address(), credentials, now(), signed, orderType, postOnly, false));
    }

    /** V2 市价单：构造 → 签名 → V2 post。 */
    public CompletableFuture<PostOrderResponse> createAndPostMarketOrderV2(
            MarketOrderArgsV2 args, CreateOrderOptions options, OrderType orderType) {
        Objects.requireNonNull(orderType, "orderType");
        if (!orderType.isMarket()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "createAndPostMarketOrderV2 requires FOK/FAK, got " + orderType));
        }
        return orderBuilder.createMarketOrderV2(args, options)
                .thenCompose(signed -> order.postOrderV2(
                        signer.address(), credentials, now(), signed, orderType, false, false));
    }

    /** V2 直接 {@code POST /order}（已签名）。 */
    public CompletableFuture<PostOrderResponse> postOrderV2(
            SignedOrderV2 signed, OrderType orderType, boolean postOnly) {
        return order.postOrderV2(signer.address(), credentials, now(), signed, orderType, postOnly, false);
    }

    /** V2 直接 {@code POST /order}，可控制 {@code deferExec}（RFQ 场景）。 */
    public CompletableFuture<PostOrderResponse> postOrderV2(
            SignedOrderV2 signed, OrderType orderType, boolean postOnly, boolean deferExec) {
        return order.postOrderV2(signer.address(), credentials, now(), signed, orderType, postOnly, deferExec);
    }

    /** {@code DELETE /order}。 */
    public CompletableFuture<CancelResponse> cancelOrder(String orderId) {
        return order.cancelOrder(signer.address(), credentials, now(), orderId);
    }

    /** {@code DELETE /orders}。 */
    public CompletableFuture<CancelResponse> cancelOrders(List<String> orderIds) {
        return order.cancelOrders(signer.address(), credentials, now(), orderIds);
    }

    /** {@code DELETE /cancel-all}。 */
    public CompletableFuture<CancelResponse> cancelAll() {
        return order.cancelAll(signer.address(), credentials, now());
    }

    /**
     * {@code DELETE /cancel-market-orders}：按 market / assetId 取消一组开放订单。
     * 至少需要提供一个过滤字段（见 {@link CancelMarketOrdersRequest}）。
     */
    public CompletableFuture<CancelResponse> cancelMarketOrders(CancelMarketOrdersRequest request) {
        return order.cancelMarketOrders(signer.address(), credentials, now(), request);
    }

    /** {@code GET /data/order/{id}}。 */
    public CompletableFuture<OpenOrder> getOrder(String orderId) {
        return order.getOrder(signer.address(), credentials, now(), orderId);
    }

    /** {@code GET /data/orders}：游标分页聚合。 */
    public Stream<OpenOrder> getOpenOrders(OpenOrderParams params) {
        return order.getOpenOrders(signer.address(), credentials, now(), params);
    }

    /** {@code GET /order-scoring}：查询单笔订单的 MMR 资格。 */
    public CompletableFuture<OrderScoringResponse> isOrderScoring(String orderId) {
        return order.isOrderScoring(signer.address(), credentials, now(), orderId);
    }

    /** {@code POST /orders-scoring}：批量查询 MMR 资格。 */
    public CompletableFuture<OrdersScoringResponse> areOrdersScoring(List<String> orderIds) {
        return order.areOrdersScoring(signer.address(), credentials, now(), orderIds);
    }

    /** {@code GET /data/trades}：分页获取交易历史。过滤条件见 {@link TradesRequest}。 */
    public Stream<Trade> getTrades(TradesRequest request) {
        return trade.getTrades(signer.address(), credentials, now(), request);
    }

    // ---------------- Heartbeat ----------------

    /**
     * {@code POST /v1/heartbeats}：发送一次心跳。调用方负责维护 {@code heartbeatId}——
     * 第一次传 {@code null}，之后每次把上次响应里返回的 id 回传。
     *
     * <p>推荐通过 {@link #startHeartbeats()} 启动后台自动调度，避免手动维护。</p>
     */
    public CompletableFuture<HeartbeatResponse> postHeartbeat(UUID heartbeatId) {
        return heartbeat.postHeartbeat(signer.address(), credentials, now(), heartbeatId);
    }

    /**
     * 启动后台心跳调度，返回一个 {@link HeartbeatScheduler} 便于显式 {@link
     * HeartbeatScheduler#stop()} 或 {@link HeartbeatScheduler#close()}。
     *
     * <p>与 Rust {@code start_heartbeats}：
     * <ul>
     *   <li>间隔默认 {@link HeartbeatScheduler#DEFAULT_INTERVAL}（5 秒）。</li>
     *   <li>自动维护 heartbeat_id 链；失败自动重试（下个 tick）。</li>
     *   <li>调用方在 {@link #close()} 前应主动 {@code stop()}，否则进程退出时线程会被 daemon 化打断。</li>
     * </ul>
     * </p>
     */
    public HeartbeatScheduler startHeartbeats() {
        return startHeartbeats(HeartbeatScheduler.DEFAULT_INTERVAL);
    }

    /** {@link #startHeartbeats()} 的自定义间隔版本。 */
    public HeartbeatScheduler startHeartbeats(Duration interval) {
        HeartbeatScheduler scheduler = new HeartbeatScheduler(this::postHeartbeat, interval);
        scheduler.start();
        return scheduler;
    }

    // ---------------- Builder promotion ----------------

    /**
     * 升级为 {@link BuilderClobClient}：
     * <ul>
     *   <li>不会吊销当前 L2 凭证，Builder 客户端共用同一份 {@link Signer} 与 L2 {@link ApiCredentials}；</li>
     *   <li>仅叠加 {@code POLY_BUILDER_*} 头，由 {@link BuilderConfig} 决定本地或远程签名；</li>
     *   <li>Java 的 typestate 不像 Rust 那样消费 self：升级后的两个 client 共享 transport/凭证，
     *       调用方自行决定是否停用原有 {@link AuthenticatedClobClient}。</li>
     * </ul>
     */
    public BuilderClobClient promoteToBuilder(BuilderConfig config) {
        Objects.requireNonNull(config, "config");
        return new BuilderClobClient(this, config);
    }

    // ---------------- WebSocket 入口 ----------------

    /**
     * 创建 {@link AuthenticatedClobWebSocketClient}，复用当前 L2 凭证。
     *
     * <p>使用 SDK 默认 WS endpoint（{@link AuthenticatedClobWebSocketClient#DEFAULT_ENDPOINT}）
     * 与默认 {@link WebSocketConfig}。返回的 client 与本对象的 REST transport 解耦——
     * 关闭一边不会影响另一边，调用方需自行 {@code close()}。</p>
     *
     * <p>注意：每次调用都会构造一份新的 WebSocket client；若需要长期持有应自行缓存，
     * 不要在请求路径中反复创建以免 connection 泄漏。</p>
     */
    public AuthenticatedClobWebSocketClient webSocket() {
        return webSocket(AuthenticatedClobWebSocketClient.DEFAULT_ENDPOINT, WebSocketConfig.defaults());
    }

    /** {@link #webSocket()} 的自定义 endpoint / 配置版本。 */
    public AuthenticatedClobWebSocketClient webSocket(URI baseEndpoint, WebSocketConfig config) {
        return AuthenticatedClobWebSocketClient.create(baseEndpoint, config, credentials);
    }

    // ---------------- package-private accessors：供 BuilderClobClient 复用 ----------------

    Signer signer() { return signer; }
    ApiCredentials credentials() { return credentials; }
    TradeApi tradeInternal() { return trade; }
    BuilderApi builderInternal() { return builder; }

    /** 包级 accessor：供 {@link BuilderClobClient} 透传 Clock，保持 parity 一致性。 */
    Clock clockInternal() { return clock; }

    /** 包级 accessor：供 {@link BuilderClobClient} 透传 SaltSource。 */
    SaltSource saltSourceInternal() { return saltSource; }

    /**
     * 查询 USDC 余额与 allowance。便利包装：自动补齐 {@link SignatureType} 与时间戳。
     */
    public CompletableFuture<BalanceAllowanceResponse> balanceAllowance(BalanceAllowanceRequest request) {
        BalanceAllowanceRequest enriched = request.withDefaultSignatureType(signatureType);
        return account.balanceAllowance(signer.address(), credentials, now(), enriched);
    }

    /** 查询 closed-only 状态。 */
    public CompletableFuture<BanStatusResponse> closedOnlyMode() {
        return account.closedOnlyMode(signer.address(), credentials, now());
    }

    /** 只读访问当前凭证的 apiKey（不暴露 secret / passphrase）。 */
    public String apiKeyId() {
        return credentials.apiKey();
    }

    /** 由 signer 持有的 EOA 地址（可能 ≠ funder）。 */
    public Address callerAddress() {
        return signer.address();
    }

    /** 包级测试辅助：允许 {@code AuthenticatedClobClient} 透出底层 transport。 */
    HttpTransport transport() { return transport; }

    private long now() {
        return clock.instant().getEpochSecond();
    }

    /**
     * 释放客户端资源。与 {@link ClobClient#close()} 同义：JDK 17 基线下为 no-op；
     * {@link HttpTransport} 透传自上游 {@link ClobClient}，关闭动作统一在 JDK 21+ 升级
     * 时再下沉（backlog N4）。多次调用安全。
     */
    @Override
    public void close() {
        // no-op；transport 由上游 ClobClient 持有，关闭契约对齐 ClobClient#close。
    }
}
