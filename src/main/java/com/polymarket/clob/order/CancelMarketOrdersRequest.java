package com.polymarket.clob.order;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.polymarket.clob.model.Hash32;

import java.math.BigInteger;

/**
 * {@code DELETE /cancel-market-orders} 的请求体，对应 Rust {@code CancelMarketOrderRequest}。
 *
 * <p>两个字段都可选，但至少需要一个：
 * <ul>
 *   <li>{@link #market}：按 conditionId 取消该市场下所有开放订单。</li>
 *   <li>{@link #assetId}：按 token / outcome id 精确取消单个 outcome 下的订单。</li>
 * </ul>
 * 当两者都提供时，过滤条件取交集（仅对同时匹配的订单取消）。
 * </p>
 *
 * <p>{@link JsonInclude.Include#NON_NULL} 保证 null 字段不出现在 wire JSON 中，
 * 与 Rust {@code Option<T>} 省略 {@code null} 的行为等价。</p>
 */
public record CancelMarketOrdersRequest(
        @JsonProperty("market") @JsonInclude(JsonInclude.Include.NON_NULL) Hash32 market,
        @JsonProperty("asset_id") @JsonInclude(JsonInclude.Include.NON_NULL) BigInteger assetId) {

    public static CancelMarketOrdersRequest ofMarket(Hash32 market) {
        return new CancelMarketOrdersRequest(market, null);
    }

    public static CancelMarketOrdersRequest ofAssetId(BigInteger assetId) {
        return new CancelMarketOrdersRequest(null, assetId);
    }

    public static CancelMarketOrdersRequest of(Hash32 market, BigInteger assetId) {
        return new CancelMarketOrdersRequest(market, assetId);
    }
}
