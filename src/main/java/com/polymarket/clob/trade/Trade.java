package com.polymarket.clob.trade;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.api.model.TradeStatusType;
import com.polymarket.clob.api.model.TraderSide;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.Hash32;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

/**
 * {@code GET /data/trades} 的每条记录，对应 Rust {@code TradeResponse}。
 *
 * <p>字段与 Rust 保持 1:1：
 * <ul>
 *   <li>{@code match_time} / {@code last_update} 服务端返回秒级 Unix 字符串，这里用
 *       {@code long} 原样承载，调用方按需转 {@link java.time.Instant}。</li>
 *   <li>{@code market}、{@code transaction_hash} 为 32 字节哈希，使用 {@link Hash32}。</li>
 *   <li>{@code asset_id} 为 uint256，使用 {@link BigInteger}。</li>
 *   <li>{@code size} / {@code price} / {@code fee_rate_bps} 以 {@link BigDecimal} 保留精度。</li>
 * </ul>
 * </p>
 */
@Value
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
public class Trade {

    @JsonProperty("id") String id;
    @JsonProperty("taker_order_id") String takerOrderId;
    @JsonProperty("market") Hash32 market;
    @JsonProperty("asset_id") BigInteger assetId;
    @JsonProperty("side") Side side;
    @JsonProperty("size") BigDecimal size;
    @JsonProperty("fee_rate_bps") BigDecimal feeRateBps;
    @JsonProperty("price") BigDecimal price;
    @JsonProperty("status") TradeStatusType status;
    /** Unix 秒字符串 → long；服务端以字符串形式返回，Jackson 透过 {@code USE_BIG_DECIMAL_FOR_FLOATS} 不影响整数。 */
    @JsonProperty("match_time") long matchTime;
    @JsonProperty("last_update") long lastUpdate;
    @JsonProperty("outcome") String outcome;
    @JsonProperty("bucket_index") int bucketIndex;
    @JsonProperty("owner") String owner;
    @JsonProperty("maker_address") Address makerAddress;
    @JsonProperty("maker_orders") List<MakerOrder> makerOrders;
    @JsonProperty("transaction_hash") Hash32 transactionHash;
    @JsonProperty("trader_side") TraderSide traderSide;
    @JsonProperty("error_msg") String errorMsg;
}
