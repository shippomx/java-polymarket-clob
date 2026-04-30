package com.polymarket.clob.trade;

import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.Hash32;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /data/trades} 与 {@code GET /builder/trades} 的 query 过滤项，
 * 对齐 Rust {@code TradesRequest}。
 *
 * <p>全部字段可选；服务端只对传入的键做过滤。所有字段 {@code null} 时等价于"无过滤"，
 * 返回当前凭证所有可见 trade。</p>
 */
public final class TradesRequest {

    private final String id;
    private final Address takerAddress;
    private final Address makerAddress;
    private final Hash32 market;
    private final BigInteger assetId;
    private final Long before;
    private final Long after;

    private TradesRequest(String id, Address taker, Address maker, Hash32 market,
                          BigInteger assetId, Long before, Long after) {
        this.id = id;
        this.takerAddress = taker;
        this.makerAddress = maker;
        this.market = market;
        this.assetId = assetId;
        this.before = before;
        this.after = after;
    }

    public static TradesRequest none() {
        return new TradesRequest(null, null, null, null, null, null, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String id() { return id; }
    public Address takerAddress() { return takerAddress; }
    public Address makerAddress() { return makerAddress; }
    public Hash32 market() { return market; }
    public BigInteger assetId() { return assetId; }
    public Long before() { return before; }
    public Long after() { return after; }

    /**
     * 生成 URL query 参数。对 {@link #market} / 地址类字段做 hex 编码；
     * {@link #assetId} 用十进制字符串（uint256 wire 习惯）。
     */
    public Map<String, String> toQueryParams() {
        Map<String, String> m = new LinkedHashMap<>();
        if (id != null && !id.isBlank()) m.put("id", id);
        if (takerAddress != null) m.put("taker", takerAddress.toLowerHex());
        if (makerAddress != null) m.put("maker", makerAddress.toLowerHex());
        if (market != null) m.put("market", market.toHex());
        if (assetId != null) m.put("asset_id", assetId.toString());
        if (before != null) m.put("before", before.toString());
        if (after != null) m.put("after", after.toString());
        return m;
    }

    public static final class Builder {
        private String id;
        private Address taker;
        private Address maker;
        private Hash32 market;
        private BigInteger assetId;
        private Long before;
        private Long after;

        public Builder id(String id) { this.id = id; return this; }
        public Builder takerAddress(Address a) { this.taker = a; return this; }
        public Builder makerAddress(Address a) { this.maker = a; return this; }
        public Builder market(Hash32 m) { this.market = m; return this; }
        public Builder assetId(BigInteger assetId) { this.assetId = assetId; return this; }
        /** Unix 秒，过滤在此时间之前（含）发生的 trade。 */
        public Builder before(long before) { this.before = before; return this; }
        /** Unix 秒，过滤在此时间之后（含）发生的 trade。 */
        public Builder after(long after) { this.after = after; return this; }

        public TradesRequest build() {
            return new TradesRequest(id, taker, maker, market, assetId, before, after);
        }
    }
}
