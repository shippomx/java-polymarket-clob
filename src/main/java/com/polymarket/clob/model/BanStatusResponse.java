package com.polymarket.clob.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code GET /auth/ban-status/closed-only} 的响应体。
 *
 * <p>{@code closed_only=true} 表示该账户只允许关仓，不能再开新仓。</p>
 */
public final class BanStatusResponse {

    private final boolean closedOnly;

    @JsonCreator
    public BanStatusResponse(@JsonProperty("closed_only") boolean closedOnly) {
        this.closedOnly = closedOnly;
    }

    public boolean closedOnly() {
        return closedOnly;
    }

    @Override
    public String toString() {
        return "BanStatusResponse{closedOnly=" + closedOnly + "}";
    }
}
