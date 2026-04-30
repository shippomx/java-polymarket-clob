package com.polymarket.clob.order;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code POST /orders-scoring} 的响应：{@code orderId -> scoring} 的扁平 map。
 * 对应 Rust 的 {@code pub type OrdersScoringResponse = HashMap<String, bool>}。
 *
 * <p>用裸 {@code Map} 一层包装，便于在 API 表面保持"类型"身份——调用方可以直接
 * {@code response.get("orderId")}；同时 Jackson 可直接反序列化整个 object。</p>
 */
public final class OrdersScoringResponse {

    private final Map<String, Boolean> scoring;

    @JsonCreator
    public OrdersScoringResponse() {
        this.scoring = new LinkedHashMap<>();
    }

    public OrdersScoringResponse(Map<String, Boolean> scoring) {
        this.scoring = new LinkedHashMap<>(scoring);
    }

    @JsonAnySetter
    void put(String key, Boolean value) {
        scoring.put(key, value);
    }

    @JsonAnyGetter
    public Map<String, Boolean> asMap() {
        return Map.copyOf(scoring);
    }

    public Boolean get(String orderId) {
        return scoring.get(orderId);
    }

    public boolean isScoring(String orderId) {
        Boolean v = scoring.get(orderId);
        return v != null && v;
    }

    public int size() {
        return scoring.size();
    }

    @Override
    public String toString() {
        return "OrdersScoringResponse" + scoring;
    }
}
