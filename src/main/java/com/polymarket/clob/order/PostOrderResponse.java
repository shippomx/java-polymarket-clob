package com.polymarket.clob.order;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * {@code POST /order} 或 {@code POST /orders} 的响应。
 *
 * <p>字段参照 py-clob-client 观察到的 payload：
 * <ul>
 *   <li>{@code success}：{@code true} 即入队成功；失败时带 {@code errorMsg}。</li>
 *   <li>{@code orderID}：单下单返回；批量下单时为空。</li>
 *   <li>{@code orderHashes}：批量返回的链上撮合哈希列表；单下单字段缺省。</li>
 *   <li>{@code transactionsHashes}：ATM 立即撮合时的链上 tx hashes。</li>
 *   <li>{@code status}：{@code "matched" / "live" / "delayed" / "unmatched" / "canceled"}。</li>
 * </ul>
 * 未知字段默认忽略，避免上游字段扩展炸毁反序列化。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PostOrderResponse(
        @JsonProperty("success") boolean success,
        @JsonProperty("errorMsg") String errorMsg,
        @JsonProperty("orderID") String orderId,
        @JsonProperty("orderHashes") List<String> orderHashes,
        @JsonProperty("transactionsHashes") List<String> transactionHashes,
        @JsonProperty("status") String status) {

    @JsonCreator
    public PostOrderResponse {
        // compact canonical form
    }
}
