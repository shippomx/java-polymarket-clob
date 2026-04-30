package com.polymarket.clob.api;

import com.polymarket.clob.api.model.MarketResponse;
import com.polymarket.clob.api.model.MidpointResponse;
import com.polymarket.clob.api.model.OrderBookSnapshot;
import com.polymarket.clob.api.model.PriceResponse;
import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.order.TickSize;

import java.util.concurrent.CompletableFuture;

/**
 * 未认证可用的只读 CLOB 接口。
 *
 * <p>首版仅暴露 6 个核心 GET 端点；批量 POST 与更多端点
 * （{@code spread} / {@code tick-size} / {@code last-trade-price} 等）留待 Plan 3。</p>
 *
 * <p>所有方法返回 {@link CompletableFuture}；失败时以
 * {@link com.polymarket.clob.exception.ClobApiException} 或
 * {@link com.polymarket.clob.exception.ClobSerializationException} 完成。</p>
 */
public interface MarketDataApi {

    /** {@code GET /}：健康检查。 */
    CompletableFuture<String> ok();

    /** {@code GET /time}：服务器时间（秒级 Unix 时间戳）。 */
    CompletableFuture<Long> serverTime();

    /**
     * {@code GET /version}：服务器协议版本（{@code 1} = CLOB v1，{@code 2} = CLOB v2）。
     *
     * <p>2026-04-28 起 Polygon 主网为 {@code 2}。客户端可在启动时调一次此端点，根据返回值
     * 决定 wire / EIP-712 路径走 V1 还是 V2；本 SDK 暂不基于此自动切换，留给上层业务判断。</p>
     *
     * <p>对齐 py-clob-client-v2 {@code get_version} 与 rs-clob-client-v2 {@code Client.version}：
     * 上游 Python 在异常时静默回落 {@code 2}；本方法在 HTTP 失败时不做静默回落，
     * 直接以 {@link com.polymarket.clob.exception.ClobApiException} 失败。</p>
     */
    CompletableFuture<Integer> serverVersion();

    /** {@code GET /midpoint?token_id=...}。 */
    CompletableFuture<MidpointResponse> getMidpoint(String tokenId);

    /** {@code GET /price?token_id=...&side=BUY|SELL}。 */
    CompletableFuture<PriceResponse> getPrice(String tokenId, Side side);

    /** {@code GET /book?token_id=...}：订单簿快照。 */
    CompletableFuture<OrderBookSnapshot> getOrderBook(String tokenId);

    /** {@code GET /markets/{conditionId}}：单个市场元数据。 */
    CompletableFuture<MarketResponse> getMarket(String conditionId);

    /**
     * {@code GET /tick-size?token_id=...}：市场最小价格变动单位。
     *
     * <p>Polymarket 仅允许 {@code 0.1 / 0.01 / 0.001 / 0.0001} 四档；超出范围抛
     * {@link IllegalArgumentException}。该接口无认证要求，订单构造前多半会调用一次。</p>
     */
    CompletableFuture<TickSize> getTickSize(String tokenId);

    /**
     * {@code GET /neg-risk?token_id=...}：多结果市场标志。
     * 决定 EIP-712 的 {@code verifyingContract} 是 CTFExchange 还是 NegRiskCTFExchange。
     */
    CompletableFuture<Boolean> getNegRisk(String tokenId);

    /**
     * {@code GET /fee-rate?token_id=...}：当前市场的 maker fee（basis points）。
     *
     * <p>下单签名时必须把这个值塞进 {@code feeRateBps}，否则服务端返回
     * {@code 400 invalid fee rate}。Rust 的 {@code OrderBuilder} 在每次构建订单前
     * 会自动调一次本接口；Java 这边为保持显式性，由调用方读完再传给
     * {@link com.polymarket.clob.order.LimitOrderArgs.LimitOrderArgsBuilder#feeRateBps(int)}。</p>
     */
    CompletableFuture<Integer> getFeeRateBps(String tokenId);
}
