package com.polymarket.clob.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.math.BigDecimal;

/**
 * 对应 Rust {@code Token}，描述某个 outcome token 的 id / 名称 / 最新价。
 *
 * <p>{@code tokenId} 上游是 uint256 字符串，保留为 {@link String} 避免精度丢失。</p>
 */
@Value
@Builder
@Jacksonized
public class Token {
    @JsonProperty("token_id") String tokenId;
    @JsonProperty("outcome") String outcome;
    @JsonProperty("price") BigDecimal price;
    @JsonProperty("winner") Boolean winner;
}
