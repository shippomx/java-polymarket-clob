package com.polymarket.clob.api;

import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.trade.BuilderTrade;
import com.polymarket.clob.trade.Trade;
import com.polymarket.clob.trade.TradesRequest;

import java.util.stream.Stream;

/**
 * 交易历史查询。两类端点：
 * <ul>
 *   <li>{@code GET /data/trades} — 普通 L2 认证，返回 {@link Trade}。</li>
 *   <li>{@code GET /builder/trades} — 额外要求 Builder 认证（POLY_BUILDER_*），
 *       仅 {@link com.polymarket.clob.BuilderClobClient} 可调用；返回 {@link BuilderTrade}。</li>
 * </ul>
 * 两个端点共享 {@link TradesRequest} 过滤项与游标分页语义，调用方无需关心分页细节：
 * 返回的 {@link Stream} 会在迭代时自动翻页直至终止游标。
 */
public interface TradeApi {

    /**
     * {@code GET /data/trades}：游标分页流式返回 {@link Trade}。
     *
     * <p>L2 签名路径不含 query（同 {@code /data/orders}），同一请求所有分页复用同一组头，
     * 与 py-clob-client 行为一致。</p>
     */
    Stream<Trade> getTrades(
            Address caller,
            ApiCredentials credentials,
            long timestamp,
            TradesRequest request);

    /**
     * {@code GET /builder/trades}：Builder 视角的交易历史，需要 Builder 扩展头。
     *
     * <p>本方法通常由 {@link com.polymarket.clob.BuilderClobClient} 封装，
     * 调用前会在 {@code extraHeaders} 中合并 {@code POLY_BUILDER_*}。</p>
     */
    Stream<BuilderTrade> getBuilderTrades(
            Address caller,
            ApiCredentials credentials,
            long timestamp,
            TradesRequest request,
            java.util.Map<String, String> extraHeaders);
}
