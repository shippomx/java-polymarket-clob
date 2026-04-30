package com.polymarket.clob.ws.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.exception.ClobSerializationException;
import com.polymarket.clob.model.Hash32;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * 发送给 Polymarket WebSocket 服务端的订阅 / 退订请求体。对应 Rust
 * {@code SubscriptionRequest}（{@code rs-clob-client/src/clob/ws/types/request.rs}）。
 *
 * <p>wire 字段对齐：</p>
 * <ul>
 *   <li>{@code type} —— "market" / "user"</li>
 *   <li>{@code operation} —— "subscribe" / "unsubscribe"（可缺省）</li>
 *   <li>{@code markets} —— 16 进制 condition id 数组（user channel 必填）</li>
 *   <li>{@code assets_ids} —— 注意是<b>复数 s</b>，erc1155 token id 字符串数组（market channel 必填）</li>
 *   <li>{@code initial_dump} —— subscribe 时是否要请求初始全量 dump</li>
 *   <li>{@code custom_feature_enabled} —— 启用 best_bid_ask / new_market / market_resolved 三类附加事件</li>
 * </ul>
 *
 * <p>user channel 还需要在 envelope 顶层注入 {@code auth: {apiKey, secret, passphrase}}，
 * 由 {@link #toAuthenticatedJson(ObjectMapper, ApiCredentials)} 完成；这不会影响普通 record
 * 字段的可见性。</p>
 *
 * <p>使用入口推荐 {@link #market(List, boolean)} / {@link #marketUnsubscribe(List)} /
 * {@link #user(List)} / {@link #userUnsubscribe(List)} 工厂方法，与 Rust SDK 对齐。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubscriptionRequest(
        @JsonProperty("type") Channel type,
        @JsonProperty("operation") Operation operation,
        @JsonProperty("markets") List<Hash32> markets,
        // U256 token id 必须以<b>字符串</b>上线（Rust SDK 通过 serde_with::DisplayFromStr 同形态）；
        // 否则 256-bit 整数会被解析方按 number 处理，多数前端 / 后端会丢精度。
        @JsonProperty("assets_ids")
        @JsonSerialize(contentUsing = ToStringSerializer.class)
        List<BigInteger> assetIds,
        @JsonProperty("initial_dump") Boolean initialDump,
        @JsonProperty("custom_feature_enabled") Boolean customFeatureEnabled) {

    public SubscriptionRequest {
        Objects.requireNonNull(type, "type");
        markets = markets == null ? List.of() : List.copyOf(markets);
        assetIds = assetIds == null ? List.of() : List.copyOf(assetIds);
    }

    /** 构造 market channel 的订阅请求。 */
    public static SubscriptionRequest market(List<BigInteger> assetIds, boolean initialDump) {
        return new SubscriptionRequest(
                Channel.MARKET,
                Operation.SUBSCRIBE,
                List.of(),
                assetIds,
                initialDump,
                null);
    }

    /** 构造 market channel 的退订请求。 */
    public static SubscriptionRequest marketUnsubscribe(List<BigInteger> assetIds) {
        return new SubscriptionRequest(
                Channel.MARKET,
                Operation.UNSUBSCRIBE,
                List.of(),
                assetIds,
                null,
                null);
    }

    /** 构造 user channel 的订阅请求（initial_dump 默认 true，与 Rust SDK 对齐）。 */
    public static SubscriptionRequest user(List<Hash32> markets) {
        return new SubscriptionRequest(
                Channel.USER,
                Operation.SUBSCRIBE,
                markets,
                List.of(),
                Boolean.TRUE,
                null);
    }

    /** 构造 user channel 的退订请求。 */
    public static SubscriptionRequest userUnsubscribe(List<Hash32> markets) {
        return new SubscriptionRequest(
                Channel.USER,
                Operation.UNSUBSCRIBE,
                markets,
                List.of(),
                null,
                null);
    }

    /**
     * 启用 custom features 标志（{@code best_bid_ask / new_market / market_resolved}），
     * 返回新实例。其他字段不动。
     */
    public SubscriptionRequest withCustomFeatures(boolean enabled) {
        return new SubscriptionRequest(type, operation, markets, assetIds, initialDump, enabled);
    }

    /** 普通（market）订阅 envelope，序列化为 JSON 字符串。 */
    public String toJson(ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        try {
            return mapper.writeValueAsString(this);
        } catch (Exception e) {
            throw new ClobSerializationException("Failed to serialize SubscriptionRequest", e);
        }
    }

    /**
     * user channel 订阅 envelope：在 envelope 顶层注入 {@code auth} 字段后再序列化，
     * 与 Rust {@code WithCredentials::as_authenticated} 输出格式一致。
     *
     * <p>调用方必须保证 {@code credentials} 与持有 wallet 派生的 L2 凭证一致；本方法
     * 不做任何签名校验，只做装填。</p>
     */
    public String toAuthenticatedJson(ObjectMapper mapper, ApiCredentials credentials) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(credentials, "credentials");
        try {
            ObjectNode envelope = mapper.valueToTree(this);
            ObjectNode auth = envelope.objectNode();
            auth.put("apiKey", credentials.apiKey());
            auth.put("secret", credentials.secret());
            auth.put("passphrase", credentials.passphrase());
            envelope.set("auth", auth);
            return mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new ClobSerializationException("Failed to serialize authenticated SubscriptionRequest", e);
        }
    }
}
