package com.polymarket.clob.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.math.BigDecimal;

/** {@code GET /price} 响应体。 */
@Value
@Builder
@Jacksonized
public class PriceResponse {
    @JsonProperty("price") BigDecimal price;
}
