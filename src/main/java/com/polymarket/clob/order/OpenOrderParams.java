package com.polymarket.clob.order;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /data/orders} 的过滤项。对齐 py-clob-client {@code OpenOrderParams}：
 * {@code id}、{@code market}、{@code asset_id} 均可选。
 *
 * <p>使用 Builder + {@link #toQueryParams()} 方便与 {@link com.polymarket.clob.pagination}
 * 搭配。</p>
 */
public final class OpenOrderParams {

    private final String id;
    private final String market;
    private final String assetId;

    private OpenOrderParams(String id, String market, String assetId) {
        this.id = id;
        this.market = market;
        this.assetId = assetId;
    }

    public static OpenOrderParams none() {
        return new OpenOrderParams(null, null, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String id() { return id; }
    public String market() { return market; }
    public String assetId() { return assetId; }

    public Map<String, String> toQueryParams() {
        Map<String, String> m = new LinkedHashMap<>();
        if (id != null && !id.isBlank()) m.put("id", id);
        if (market != null && !market.isBlank()) m.put("market", market);
        if (assetId != null && !assetId.isBlank()) m.put("asset_id", assetId);
        return m;
    }

    public static final class Builder {
        private String id;
        private String market;
        private String assetId;

        public Builder id(String id) { this.id = id; return this; }
        public Builder market(String market) { this.market = market; return this; }
        public Builder assetId(String assetId) { this.assetId = assetId; return this; }

        public OpenOrderParams build() {
            return new OpenOrderParams(id, market, assetId);
        }
    }
}
