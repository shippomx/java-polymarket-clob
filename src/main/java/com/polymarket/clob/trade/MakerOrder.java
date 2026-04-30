package com.polymarket.clob.trade;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.model.Address;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * {@link Trade#makerOrders()} 中每一笔对手单的快照，对应 Rust {@code MakerOrder}：
 * 记录哪条 maker 单被撮合、成交数量、方向等信息。
 */
@Value
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
public class MakerOrder {

    @JsonProperty("order_id") String orderId;
    @JsonProperty("owner") String owner;
    @JsonProperty("maker_address") Address makerAddress;
    @JsonProperty("matched_amount") BigDecimal matchedAmount;
    @JsonProperty("price") BigDecimal price;
    @JsonProperty("fee_rate_bps") BigDecimal feeRateBps;
    @JsonProperty("asset_id") BigInteger assetId;
    @JsonProperty("outcome") String outcome;
    @JsonProperty("side") Side side;
}
