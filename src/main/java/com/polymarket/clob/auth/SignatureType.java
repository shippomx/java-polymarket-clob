package com.polymarket.clob.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 订单签名方/funder 的资金所有者模型。
 *
 * <p>序列化采用整数编码（与 Rust {@code Serialize_repr} 对齐）。
 * V2 上线后仅保留 {@link #EOA}=0 与 {@link #POLY_1271}=3；
 * Proxy（1）与 GnosisSafe（2）已随 Safe 流退役。</p>
 *
 * <p>反序列化同时接受整数与规范名称，以便容错上游未来切换。</p>
 *
 * <p><b>V1/V2 兼容性</b>：{@link #POLY_1271} 不可用于 V1 订单签名；OrderBuilder V1 路径需在使用前
 * 显式拒绝（参考 py-clob-client-v2 {@code OrderBuilder.build_order} 的 raise）。</p>
 */
public enum SignatureType {
    EOA(0),
    /** EIP-1271 合约签名（Deposit Wallet）；仅 V2 订单可用。 */
    POLY_1271(3);

    private final int code;

    SignatureType(int code) {
        this.code = code;
    }

    @JsonValue
    public int code() {
        return code;
    }

    /** 查询串取值。与 {@link #code()} 保持一致的数字字符串，避免拼接出错。 */
    public String toQueryValue() {
        return Integer.toString(code);
    }

    @JsonCreator
    public static SignatureType fromJson(Object raw) {
        if (raw instanceof Number n) {
            int c = n.intValue();
            for (SignatureType t : values()) {
                if (t.code == c) return t;
            }
            throw new IllegalArgumentException("Unknown SignatureType code: " + c);
        }
        if (raw instanceof String s) {
            for (SignatureType t : values()) {
                if (t.name().equals(s)) return t;
            }
            throw new IllegalArgumentException("Unknown SignatureType name: " + s);
        }
        throw new IllegalArgumentException("Cannot parse SignatureType from: " + raw);
    }
}
