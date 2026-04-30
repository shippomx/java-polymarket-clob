package com.polymarket.clob.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code GET /neg-risk} 响应：{@code {"neg_risk": true|false}}。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NegRiskResponse(@JsonProperty("neg_risk") boolean negRisk) {
}
