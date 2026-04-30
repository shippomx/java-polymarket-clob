package com.polymarket.clob.ws.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.api.model.TraderSide;
import com.polymarket.clob.http.JsonCodec;
import com.polymarket.clob.model.Hash32;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WsMessage} sealed 子类型反序列化测试。
 *
 * <p>基础 fixture 全部抄自 Rust 单测（{@code rs-clob-client/src/clob/ws/types/response.rs}），
 * 保证 wire 形态一致性。重点验证：</p>
 * <ul>
 *   <li>{@code event_type} 分发到正确的 sealed permit；</li>
 *   <li>{@code timestamp} 字符串 ↔ {@code long} 自动 coerce；</li>
 *   <li>{@code asset_id} {@code U256} 字符串 ↔ {@link BigInteger}；</li>
 *   <li>{@code market} hex 字符串 ↔ {@link Hash32}；</li>
 *   <li>{@code price/size} 数字字符串 ↔ {@link BigDecimal}；</li>
 *   <li>嵌套 {@code maker_orders} 与 {@code price_changes} 列表正确装载。</li>
 * </ul>
 */
class WsMessageTest {

    private static final ObjectMapper MAPPER = JsonCodec.objectMapper();

    private static final String LARGE_ASSET_ID =
            "106585164761922456203746651621390029417453862034640469075081961934906147433548";
    private static final String MARKET_HEX =
            "0x0000000000000000000000000000000000000000000000000000000000000001";

    @Test
    void parseBookSnapshot() throws Exception {
        String json = """
                {
                  "event_type": "book",
                  "asset_id": "%s",
                  "market": "%s",
                  "timestamp": "1234567890",
                  "bids": [{"price":"0.5","size":"100"}],
                  "asks": [{"price":"0.51","size":"50"}],
                  "hash": "deadbeef"
                }
                """.formatted(LARGE_ASSET_ID, MARKET_HEX);

        WsMessage msg = MAPPER.readValue(json, WsMessage.class);
        assertThat(msg).isInstanceOf(BookUpdate.class);
        BookUpdate book = (BookUpdate) msg;
        assertThat(book.assetId()).isEqualTo(new BigInteger(LARGE_ASSET_ID));
        assertThat(book.market()).isEqualTo(Hash32.fromHex(MARKET_HEX));
        assertThat(book.timestamp()).isEqualTo(1234567890L);
        assertThat(book.bids()).hasSize(1);
        assertThat(book.bids().get(0).price()).isEqualByComparingTo("0.5");
        assertThat(book.asks().get(0).size()).isEqualByComparingTo("50");
        assertThat(book.hash()).isEqualTo("deadbeef");
        assertThat(msg.isMarket()).isTrue();
        assertThat(msg.isUser()).isFalse();
    }

    @Test
    void parseBookHandlesMissingBidsAsks() throws Exception {
        String json = """
                {
                  "event_type": "book",
                  "asset_id": "%s",
                  "market": "%s",
                  "timestamp": "1"
                }
                """.formatted(LARGE_ASSET_ID, MARKET_HEX);
        BookUpdate book = (BookUpdate) MAPPER.readValue(json, WsMessage.class);
        // 即便 wire 完全没给 bids/asks，record 规范构造器也会兜底成空列表。
        assertThat(book.bids()).isEmpty();
        assertThat(book.asks()).isEmpty();
    }

    @Test
    void parsePriceChangeBatchEntries() throws Exception {
        String json = """
                {
                  "event_type": "price_change",
                  "market": "%s",
                  "timestamp": "1700000000",
                  "price_changes": [
                    {"asset_id":"%s","price":"0.10","side":"BUY","best_bid":"0.11","best_ask":"0.12","hash":"abc"},
                    {"asset_id":"%s","price":"0.90","size":"5","side":"SELL"}
                  ]
                }
                """.formatted(MARKET_HEX, LARGE_ASSET_ID, LARGE_ASSET_ID);

        PriceChange pc = (PriceChange) MAPPER.readValue(json, WsMessage.class);
        assertThat(pc.market()).isEqualTo(Hash32.fromHex(MARKET_HEX));
        assertThat(pc.timestamp()).isEqualTo(1700000000L);
        assertThat(pc.priceChanges()).hasSize(2);

        PriceChangeBatchEntry first = pc.priceChanges().get(0);
        assertThat(first.assetId()).isEqualTo(new BigInteger(LARGE_ASSET_ID));
        assertThat(first.price()).isEqualByComparingTo("0.10");
        assertThat(first.side()).isEqualTo(Side.BUY);
        assertThat(first.bestBid()).isEqualByComparingTo("0.11");
        assertThat(first.bestAsk()).isEqualByComparingTo("0.12");
        assertThat(first.size()).isNull();

        PriceChangeBatchEntry second = pc.priceChanges().get(1);
        assertThat(second.size()).isEqualByComparingTo("5");
        assertThat(second.bestBid()).isNull();
    }

    @Test
    void parseTickSizeChange() throws Exception {
        String json = """
                {
                  "event_type": "tick_size_change",
                  "asset_id": "%s",
                  "market": "%s",
                  "old_tick_size": "0.01",
                  "new_tick_size": "0.001",
                  "timestamp": "1700000000"
                }
                """.formatted(LARGE_ASSET_ID, MARKET_HEX);
        TickSizeChange tsc = (TickSizeChange) MAPPER.readValue(json, WsMessage.class);
        assertThat(tsc.oldTickSize()).isEqualByComparingTo("0.01");
        assertThat(tsc.newTickSize()).isEqualByComparingTo("0.001");
    }

    @Test
    void parseLastTradePrice() throws Exception {
        String json = """
                {
                  "event_type": "last_trade_price",
                  "asset_id": "%s",
                  "market": "%s",
                  "price": "0.6",
                  "side": "BUY",
                  "size": "12.5",
                  "fee_rate_bps": "10",
                  "timestamp": "1700000003"
                }
                """.formatted(LARGE_ASSET_ID, MARKET_HEX);
        LastTradePrice ltp = (LastTradePrice) MAPPER.readValue(json, WsMessage.class);
        assertThat(ltp.price()).isEqualByComparingTo("0.6");
        assertThat(ltp.side()).isEqualTo(Side.BUY);
        assertThat(ltp.feeRateBps()).isEqualByComparingTo("10");
    }

    @Test
    void parseUserTradeWithMakerOrders() throws Exception {
        String json = """
                {
                  "event_type": "trade",
                  "id": "trade-1",
                  "market": "%s",
                  "asset_id": "%s",
                  "side": "BUY",
                  "size": "10",
                  "price": "0.5",
                  "status": "MATCHED",
                  "type": "TRADE",
                  "matchtime": "1700000010",
                  "last_update": "1700000011",
                  "timestamp": "1700000012",
                  "outcome": "YES",
                  "owner": "00000000-0000-0000-0000-000000000000",
                  "trade_owner": "11111111-1111-1111-1111-111111111111",
                  "taker_order_id": "ord-taker",
                  "maker_orders": [
                    {"asset_id":"%s","matched_amount":"3","order_id":"ord-m1","outcome":"YES","owner":"00000000-0000-0000-0000-000000000000","price":"0.5"}
                  ],
                  "fee_rate_bps": "0",
                  "transaction_hash": "%s",
                  "trader_side": "TAKER"
                }
                """.formatted(MARKET_HEX, LARGE_ASSET_ID, LARGE_ASSET_ID, MARKET_HEX);

        WsMessage msg = MAPPER.readValue(json, WsMessage.class);
        assertThat(msg.isUser()).isTrue();
        TradeMessage trade = (TradeMessage) msg;
        assertThat(trade.id()).isEqualTo("trade-1");
        assertThat(trade.side()).isEqualTo(Side.BUY);
        assertThat(trade.status()).isEqualTo("MATCHED");
        assertThat(trade.matchTime()).isEqualTo(1700000010L);
        assertThat(trade.transactionHash()).isEqualTo(Hash32.fromHex(MARKET_HEX));
        assertThat(trade.traderSide()).isEqualTo(TraderSide.TAKER);
        assertThat(trade.makerOrders()).hasSize(1);
        assertThat(trade.makerOrders().get(0).orderId()).isEqualTo("ord-m1");
    }

    @Test
    void parseUserTradeAcceptsMatchTimeAlias() throws Exception {
        // 历史 Polymarket WS 报文里也用过 "match_time"（蛇形 vs 拼写）；alias 处理。
        String json = """
                {
                  "event_type": "trade",
                  "id": "trade-2",
                  "market": "%s",
                  "asset_id": "%s",
                  "side": "SELL",
                  "size": "1",
                  "price": "0.5",
                  "status": "MATCHED",
                  "match_time": "1700000099"
                }
                """.formatted(MARKET_HEX, LARGE_ASSET_ID);
        TradeMessage trade = (TradeMessage) MAPPER.readValue(json, WsMessage.class);
        assertThat(trade.matchTime()).isEqualTo(1700000099L);
    }

    @Test
    void parseUserOrderUpdate() throws Exception {
        String json = """
                {
                  "event_type": "order",
                  "id": "ord-1",
                  "market": "%s",
                  "asset_id": "%s",
                  "side": "SELL",
                  "price": "0.6",
                  "type": "PLACEMENT",
                  "outcome": "YES",
                  "owner": "00000000-0000-0000-0000-000000000000",
                  "order_owner": "00000000-0000-0000-0000-000000000000",
                  "original_size": "10",
                  "size_matched": "0",
                  "timestamp": "1700000100",
                  "associate_trades": [],
                  "status": "LIVE"
                }
                """.formatted(MARKET_HEX, LARGE_ASSET_ID);
        OrderMessage ord = (OrderMessage) MAPPER.readValue(json, WsMessage.class);
        assertThat(ord.id()).isEqualTo("ord-1");
        assertThat(ord.messageType()).isEqualTo("PLACEMENT");
        assertThat(ord.status()).isEqualTo("LIVE");
        assertThat(ord.originalSize()).isEqualByComparingTo("10");
        assertThat(ord.associateTrades()).isEmpty();
    }

    @Test
    void parseTolerantToUnknownEventType() throws Exception {
        // 上游若推出未列入 sealed 的新 event_type，我们当前会让 Jackson 抛错，
        // 这是有意为之：升级 SDK 时可以发现新增类型。这里断言抛出而不是静默吞。
        String json = """
                {"event_type":"best_bid_ask","market":"%s"}
                """.formatted(MARKET_HEX);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> MAPPER.readValue(json, WsMessage.class));
    }

    @Test
    void wsMessageHelpersClassifyChannels() {
        WsMessage book = new BookUpdate(
                BigInteger.ONE, Hash32.fromHex(MARKET_HEX), 0L, List.of(), List.of(), null);
        assertThat(book.isMarket()).isTrue();
        assertThat(book.isUser()).isFalse();

        WsMessage order = new OrderMessage(
                "id", Hash32.fromHex(MARKET_HEX), BigInteger.ONE, Side.BUY, BigDecimal.ZERO,
                "PLACEMENT", null, null, null, null, null, null, List.of(), null);
        assertThat(order.isUser()).isTrue();
    }
}
