package com.polymarket.clob.trade;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.api.model.TradeStatusType;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.Hash32;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * {@code GET /builder/trades} 的每条记录，对应 Rust {@code BuilderTradeResponse}。
 *
 * <p>相对 {@link Trade}：字段走 <b>camelCase</b> wire（Rust 注解 {@code serde(rename_all = "camelCase")}），
 * 因此用 {@link JsonNaming} 统一声明；个别历史字段名（例如 {@code err_msg}）走 {@link JsonAlias} 兼容。</p>
 */
@Value
@Builder
@Jacksonized
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class BuilderTrade {

    String id;
    String tradeType;
    Hash32 takerOrderHash;
    Address builder;
    Hash32 market;
    BigInteger assetId;
    Side side;
    BigDecimal size;
    BigDecimal sizeUsdc;
    BigDecimal price;
    TradeStatusType status;
    String outcome;
    int outcomeIndex;
    String owner;
    Address maker;
    Hash32 transactionHash;
    /** 匹配时间：服务端以秒级 Unix 字符串返回，这里原样承载。 */
    long matchTime;
    int bucketIndex;
    BigDecimal fee;
    BigDecimal feeUsdc;
    /** Rust 既接受 {@code errMsg} 也接受历史别名 {@code err_msg}；这里显式别名兼容。 */
    @JsonAlias({"err_msg"})
    String errMsg;
    String createdAt;
    String updatedAt;

    @JsonProperty("errMsg")
    public String errMsg() {
        return errMsg;
    }
}
