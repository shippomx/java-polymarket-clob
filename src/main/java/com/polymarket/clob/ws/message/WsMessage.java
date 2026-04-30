package com.polymarket.clob.ws.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Polymarket CLOB WebSocket 顶层消息封装，按 wire 字段 {@code event_type} 分发到具体子类型。
 *
 * <p>对应 Rust {@code WsMessage}（{@code rs-clob-client/src/clob/ws/types/response.rs}）：
 * 同一连接上推送的所有事件都解析到此 sealed 接口，调用方按 {@code instanceof} 判定与
 * record pattern matching 处理。</p>
 *
 * <p>覆盖 channel：</p>
 * <ul>
 *   <li><b>market</b>（公共）：{@link BookUpdate} / {@link PriceChange} /
 *       {@link TickSizeChange} / {@link LastTradePrice}</li>
 *   <li><b>user</b>（认证）：{@link OrderMessage} / {@link TradeMessage}</li>
 * </ul>
 *
 * <p>说明：Rust 端还有 {@code best_bid_ask / new_market / market_resolved} 三类
 * "custom features" 推送，受订阅时 {@code custom_feature_enabled} 控制。Java 首版
 * 不实现这三种，留待后续按需要扩展（届时 sealed permits 增补即可，对应 record
 * 加上 {@code @JsonSubTypes.Type} 注册）。</p>
 *
 * <p>Jackson 分发：使用 {@code event_type} 作为 EXISTING_PROPERTY，不消费——这样在
 * 未来添加 sealed permit 时可以零侵入地为子类型保留 {@code event_type} 字段（如果
 * 调用方想读）。当前所有 record 都通过 {@code @JsonIgnoreProperties} 忽略它。</p>
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "event_type",
        visible = false)
@JsonSubTypes({
        @JsonSubTypes.Type(value = BookUpdate.class, name = "book"),
        @JsonSubTypes.Type(value = PriceChange.class, name = "price_change"),
        @JsonSubTypes.Type(value = TickSizeChange.class, name = "tick_size_change"),
        @JsonSubTypes.Type(value = LastTradePrice.class, name = "last_trade_price"),
        @JsonSubTypes.Type(value = OrderMessage.class, name = "order"),
        @JsonSubTypes.Type(value = TradeMessage.class, name = "trade"),
})
public sealed interface WsMessage
        permits BookUpdate, PriceChange, TickSizeChange, LastTradePrice, OrderMessage, TradeMessage {

    /** 是否属于 user channel（{@link OrderMessage} / {@link TradeMessage}）。 */
    default boolean isUser() {
        return this instanceof OrderMessage || this instanceof TradeMessage;
    }

    /** 是否属于 market channel（即非 user channel）。 */
    default boolean isMarket() {
        return !isUser();
    }
}
