package com.polymarket.clob.order;

import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EIP-712 Order 签名金值对照测试。
 *
 * <p>向量取自上游 <a href="https://github.com/Polymarket/python-order-utils">
 * python-order-utils</a> 的 {@code test_order_builder.py}，使用公开已知测试私钥。
 * 任何破坏性变更都会在这里先炸出来。</p>
 */
class EIP712OrderSignerTest {

    // Well-known public test key — same as used in py-order-utils tests。
    private static final String PRIVATE_KEY =
            "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    private static final Address MAKER =
            Address.fromHex("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266");

    private static final long CHAIN_ID = 80002L;
    private static final Address EXCHANGE =
            Address.fromHex("0xdFE02Eb6733538f8Ea35D585af8DE5958AD99E40");
    private static final Address NEG_RISK_EXCHANGE =
            Address.fromHex("0xC5d563A36AE78145C45a50134d48A1215220f80a");

    private static final BigInteger SALT = BigInteger.valueOf(479_249_096_354L);

    private static Order fixtureOrder() {
        return Order.builder()
                .salt(SALT)
                .maker(MAKER)
                .signer(MAKER)
                .taker(Address.ZERO)
                .tokenId(BigInteger.valueOf(1234))
                .makerAmount(BigInteger.valueOf(100_000_000L))
                .takerAmount(BigInteger.valueOf(50_000_000L))
                .expiration(BigInteger.ZERO)
                .nonce(BigInteger.ZERO)
                .feeRateBps(BigInteger.valueOf(100))
                .side(Side.BUY)
                .signatureType(SignatureType.EOA)
                .build();
    }

    @Test
    void exchange_struct_hash_matches_upstream() {
        byte[] digest = EIP712OrderSigner.hash(fixtureOrder(), CHAIN_ID, EXCHANGE);
        String hex = "0x" + HexFormat.of().formatHex(digest);
        assertThat(hex).isEqualTo(
                "0x02ca1d1aa31103804173ad1acd70066cb6c1258a4be6dada055111f9a7ea4e55");
    }

    @Test
    void neg_risk_struct_hash_matches_upstream() {
        byte[] digest = EIP712OrderSigner.hash(fixtureOrder(), CHAIN_ID, NEG_RISK_EXCHANGE);
        String hex = "0x" + HexFormat.of().formatHex(digest);
        assertThat(hex).isEqualTo(
                "0xf15790d3edc4b5aed427b0b543a9206fcf4b1a13dfed016d33bfb313076263b8");
    }

    @Test
    void exchange_signature_matches_upstream() throws Exception {
        LocalSigner signer = LocalSigner.fromPrivateKey(PRIVATE_KEY);

        byte[] digest = EIP712OrderSigner.hash(fixtureOrder(), CHAIN_ID, EXCHANGE);
        byte[] sig = signer.signHash(digest).get();
        String hex = "0x" + HexFormat.of().formatHex(sig);

        assertThat(hex).isEqualTo(
                "0x302cd9abd0b5fcaa202a344437ec0b6660da984e24ae9ad915a592a90facf5a5"
                        + "1bb8a873cd8d270f070217fea1986531d5eec66f1162a81f66e026db653bf7ce1c");
    }

    @Test
    void neg_risk_signature_matches_upstream() throws Exception {
        LocalSigner signer = LocalSigner.fromPrivateKey(PRIVATE_KEY);

        byte[] digest = EIP712OrderSigner.hash(fixtureOrder(), CHAIN_ID, NEG_RISK_EXCHANGE);
        byte[] sig = signer.signHash(digest).get();
        String hex = "0x" + HexFormat.of().formatHex(sig);

        assertThat(hex).isEqualTo(
                "0x1b3646ef347e5bd144c65bd3357ba19c12c12abaeedae733cf8579bc51a2752c"
                        + "0454c3bc6b236957e393637982c769b8dc0706c0f5c399983d933850afd1cbcd1c");
    }
}
