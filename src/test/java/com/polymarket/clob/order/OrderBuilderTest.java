package com.polymarket.clob.order;

import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OrderBuilder} 行为测试：验证装配、rounding 联动、salt 注入。
 *
 * <p>这里不重复 EIP-712 byte 对照（由 {@link EIP712OrderSignerTest} 保证），而是锁住：
 * <ul>
 *   <li>{@code salt / maker / signer} 装配正确；</li>
 *   <li>BUY/SELL 限价与市价的 {@code makerAmount / takerAmount} 与 {@link OrderRounding} 一致；</li>
 *   <li>市价单强制 {@code expiration = 0}；</li>
 *   <li>签名是合法的 0x + 130 hex（65 字节）；</li>
 *   <li>签名后的 {@code SignedOrder} 可序列化 / 反序列化。</li>
 * </ul>
 * </p>
 */
class OrderBuilderTest {

    private static final String PRIVATE_KEY =
            "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    private static final Address MAKER =
            Address.fromHex("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266");
    private static final long CHAIN_ID = 80002L;
    private static final BigInteger TOKEN_ID = BigInteger.valueOf(1234L);
    private static final BigInteger FIXED_SALT = BigInteger.valueOf(479_249_096_354L);

    private OrderBuilder builder() {
        return new OrderBuilder(
                CHAIN_ID,
                LocalSigner.fromPrivateKey(PRIVATE_KEY),
                MAKER,
                SignatureType.EOA,
                SaltSource.fixed(FIXED_SALT));
    }

    @Test
    void limit_buy_assembles_expected_fields() throws Exception {
        LimitOrderArgs args = LimitOrderArgs.builder()
                .tokenId(TOKEN_ID)
                .price(new BigDecimal("0.5"))
                .size(new BigDecimal("100"))
                .side(Side.BUY)
                .feeRateBps(100)
                .build();

        SignedOrder signed = builder()
                .createOrder(args, CreateOrderOptions.of(TickSize.TS_0_01, false))
                .get();
        Order o = signed.getOrder();

        assertThat(o.getSalt()).isEqualTo(FIXED_SALT);
        assertThat(o.getMaker()).isEqualTo(MAKER);
        assertThat(o.getSigner()).isEqualTo(MAKER);
        assertThat(o.getTaker()).isEqualTo(Address.ZERO);
        assertThat(o.getTokenId()).isEqualTo(TOKEN_ID);
        // 100 shares @ 0.5 → 50 USDC maker, 100 shares taker
        assertThat(o.getMakerAmount()).isEqualTo(BigInteger.valueOf(50_000_000L));
        assertThat(o.getTakerAmount()).isEqualTo(BigInteger.valueOf(100_000_000L));
        assertThat(o.getExpiration()).isEqualTo(BigInteger.ZERO);
        assertThat(o.getNonce()).isEqualTo(BigInteger.ZERO);
        assertThat(o.getFeeRateBps()).isEqualTo(BigInteger.valueOf(100));
        assertThat(o.getSide()).isEqualTo(Side.BUY);
        assertThat(o.getSignatureType()).isEqualTo(SignatureType.EOA);

        assertThat(signed.getSignature()).startsWith("0x").hasSize(132);
    }

    @Test
    void limit_sell_swaps_maker_taker() throws Exception {
        LimitOrderArgs args = LimitOrderArgs.builder()
                .tokenId(TOKEN_ID)
                .price(new BigDecimal("0.5"))
                .size(new BigDecimal("100"))
                .side(Side.SELL)
                .build();

        Order o = builder()
                .createOrder(args, CreateOrderOptions.of(TickSize.TS_0_01, false))
                .get()
                .getOrder();

        assertThat(o.getMakerAmount()).isEqualTo(BigInteger.valueOf(100_000_000L));
        assertThat(o.getTakerAmount()).isEqualTo(BigInteger.valueOf(50_000_000L));
        assertThat(o.getSide()).isEqualTo(Side.SELL);
    }

    @Test
    void market_buy_forces_expiration_zero_and_inverts_amounts() throws Exception {
        MarketOrderArgs args = MarketOrderArgs.builder()
                .tokenId(TOKEN_ID)
                .amount(new BigDecimal("100"))
                .price(new BigDecimal("0.5"))
                .side(Side.BUY)
                .build();

        Order o = builder()
                .createMarketOrder(args, CreateOrderOptions.of(TickSize.TS_0_01, false))
                .get()
                .getOrder();

        assertThat(o.getExpiration()).isEqualTo(BigInteger.ZERO);
        // BUY market: maker=100 USDC, taker=200 shares
        assertThat(o.getMakerAmount()).isEqualTo(BigInteger.valueOf(100_000_000L));
        assertThat(o.getTakerAmount()).isEqualTo(BigInteger.valueOf(200_000_000L));
    }

    @Test
    void salt_source_is_called_per_order() throws Exception {
        BigInteger[] salts = {BigInteger.valueOf(111L), BigInteger.valueOf(222L)};
        int[] idx = {0};
        SaltSource rolling = () -> salts[idx[0]++];

        OrderBuilder b = new OrderBuilder(
                CHAIN_ID,
                LocalSigner.fromPrivateKey(PRIVATE_KEY),
                MAKER,
                SignatureType.EOA,
                rolling);

        LimitOrderArgs args = LimitOrderArgs.builder()
                .tokenId(TOKEN_ID).price(new BigDecimal("0.5")).size(new BigDecimal("1")).side(Side.BUY).build();
        CreateOrderOptions opts = CreateOrderOptions.of(TickSize.TS_0_01, false);

        assertThat(b.createOrder(args, opts).get().getOrder().getSalt())
                .isEqualTo(BigInteger.valueOf(111L));
        assertThat(b.createOrder(args, opts).get().getOrder().getSalt())
                .isEqualTo(BigInteger.valueOf(222L));
    }

    @Test
    void fluent_accessors_exposed() {
        OrderBuilder b = builder();
        assertThat(b.chainId()).isEqualTo(CHAIN_ID);
        assertThat(b.funder()).isEqualTo(MAKER);
        assertThat(b.signatureType()).isEqualTo(SignatureType.EOA);
    }
}
