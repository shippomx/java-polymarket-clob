package com.polymarket.clob.model;

import com.polymarket.clob.auth.SignatureType;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code GET /balance-allowance} 的查询参数。
 *
 * <p>对应 Rust {@code BalanceAllowanceRequest}；Rust 以 {@code serde_html_form} 拼成
 * {@code ?asset_type=COLLATERAL&token_id=1&signature_type=0}，这里直接用 {@link LinkedHashMap}
 * 保持参数顺序（便于 CDN 缓存命中 + 日志比对）。</p>
 */
public final class BalanceAllowanceRequest {

    private final AssetType assetType;
    private final Optional<BigInteger> tokenId;
    private final Optional<SignatureType> signatureType;

    private BalanceAllowanceRequest(
            AssetType assetType,
            Optional<BigInteger> tokenId,
            Optional<SignatureType> signatureType) {
        this.assetType = assetType;
        this.tokenId = tokenId;
        this.signatureType = signatureType;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 构造 query 参数。Rust client 先把 {@link SignatureType} 以整数字符串 {@code 0/1/2} 拼接；
     * 这里保持同样的表达方式（见 {@link SignatureType#toQueryValue()}）。
     */
    public Map<String, Object> toQueryParams() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asset_type", assetType.toQueryValue());
        tokenId.ifPresent(id -> m.put("token_id", id.toString()));
        signatureType.ifPresent(st -> m.put("signature_type", st.toQueryValue()));
        return m;
    }

    public AssetType assetType() { return assetType; }
    public Optional<BigInteger> tokenId() { return tokenId; }
    public Optional<SignatureType> signatureType() { return signatureType; }

    /** 产生一个副本，把 {@code signatureType} 补齐为默认值（调用方未显式指定时使用）。 */
    public BalanceAllowanceRequest withDefaultSignatureType(SignatureType fallback) {
        Objects.requireNonNull(fallback, "fallback");
        if (signatureType.isPresent()) return this;
        return new BalanceAllowanceRequest(assetType, tokenId, Optional.of(fallback));
    }

    public static final class Builder {
        private AssetType assetType;
        private Optional<BigInteger> tokenId = Optional.empty();
        private Optional<SignatureType> signatureType = Optional.empty();

        public Builder assetType(AssetType assetType) {
            this.assetType = Objects.requireNonNull(assetType, "assetType");
            return this;
        }

        public Builder tokenId(BigInteger tokenId) {
            this.tokenId = Optional.ofNullable(tokenId);
            return this;
        }

        public Builder signatureType(SignatureType signatureType) {
            this.signatureType = Optional.ofNullable(signatureType);
            return this;
        }

        public BalanceAllowanceRequest build() {
            if (assetType == null) {
                throw new IllegalStateException("assetType is required");
            }
            return new BalanceAllowanceRequest(assetType, tokenId, signatureType);
        }
    }
}
