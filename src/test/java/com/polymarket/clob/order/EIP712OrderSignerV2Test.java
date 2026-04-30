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
 * V2 Order EIP-712 黄金向量回归测试（2026-04-28 CTF Exchange v2 上线后新增）。
 *
 * <p>黄金值由 py-clob-client-v2 同款 {@code ExchangeOrderBuilderV2} 路径生成
 * （eth_account.encode_typed_data + eth_utils.keccak），脚本见
 * {@code /tmp/v2_fixture.py}。任何破坏性变更都会先在这里炸出来。</p>
 *
 * <p>固定 fixture：
 * <ul>
 *   <li>chain=Polygon (137)；</li>
 *   <li>maker = signer = {@code 0xf39F..2266}（well-known test EOA）；</li>
 *   <li>salt=479249096354 / tokenId=1234 / makerAmount=1e8 / takerAmount=5e7 / side=BUY；</li>
 *   <li>timestamp=1_700_000_000_000 ms / expiration=0 / metadata=builder=0x00..0；</li>
 *   <li>signatureType=EOA。</li>
 * </ul>
 * </p>
 */
class EIP712OrderSignerV2Test {

    private static final String PRIVATE_KEY =
            "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    private static final Address EOA =
            Address.fromHex("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266");

    private static final long CHAIN_ID = 137L;
    private static final Address V2_EXCHANGE =
            Address.fromHex("0xE111180000d2663C0091e4f400237545B87B996B");
    private static final Address V2_NEG_RISK_EXCHANGE =
            Address.fromHex("0xe2222d279d744050d28e00520010520000310F59");

    private static final BigInteger SALT = BigInteger.valueOf(479_249_096_354L);
    private static final String BYTES32_ZERO = "0x" + "00".repeat(32);

    private static OrderV2 fixtureOrder() {
        return OrderV2.builder()
                .salt(SALT)
                .maker(EOA)
                .signer(EOA)
                .tokenId(BigInteger.valueOf(1234))
                .makerAmount(BigInteger.valueOf(100_000_000L))
                .takerAmount(BigInteger.valueOf(50_000_000L))
                .side(Side.BUY)
                .signatureType(SignatureType.EOA)
                .expiration(BigInteger.ZERO)
                .timestamp(BigInteger.valueOf(1_700_000_000_000L))
                .metadata(BYTES32_ZERO)
                .builder(BYTES32_ZERO)
                .build();
    }

    @Test
    void v2_exchange_struct_hash_matches_pyclob() {
        byte[] digest = EIP712OrderSigner.hashV2(fixtureOrder(), CHAIN_ID, V2_EXCHANGE);
        assertThat("0x" + HexFormat.of().formatHex(digest)).isEqualTo(
                "0x2c4f653403e777a5c37576868ba70200858e75bbc7f0d187a2ff11e5d5380e91");
    }

    @Test
    void v2_neg_risk_struct_hash_matches_pyclob() {
        byte[] digest = EIP712OrderSigner.hashV2(fixtureOrder(), CHAIN_ID, V2_NEG_RISK_EXCHANGE);
        assertThat("0x" + HexFormat.of().formatHex(digest)).isEqualTo(
                "0xb90ca1eda493daa14975a635e0b002ddefc2ba80a7784fdac3c0a7a7da2cebb4");
    }

    @Test
    void v2_exchange_signature_matches_pyclob() throws Exception {
        LocalSigner signer = LocalSigner.fromPrivateKey(PRIVATE_KEY);
        byte[] digest = EIP712OrderSigner.hashV2(fixtureOrder(), CHAIN_ID, V2_EXCHANGE);
        byte[] sig = signer.signHash(digest).get();
        assertThat("0x" + HexFormat.of().formatHex(sig)).isEqualTo(
                "0x808bcc18d94bfe59daf619655ab83ff5701a82c0685255a7e3aeeeff7a7f8350"
                        + "1984f86f573ef685b749c11eeacc57ead0c148a58b5460ee4a1c45708a290c511c");
    }

    @Test
    void v2_neg_risk_signature_matches_pyclob() throws Exception {
        LocalSigner signer = LocalSigner.fromPrivateKey(PRIVATE_KEY);
        byte[] digest = EIP712OrderSigner.hashV2(fixtureOrder(), CHAIN_ID, V2_NEG_RISK_EXCHANGE);
        byte[] sig = signer.signHash(digest).get();
        assertThat("0x" + HexFormat.of().formatHex(sig)).isEqualTo(
                "0xd5548fea0af1e86997a7bb873e8372fa6fb2e881ea00bcd9fc86f8223a5dc933"
                        + "702b3418c8b4d1ef569b2f7171a4a1b8bd8690f3adc10715f38b5fb0de3b42641c");
    }

    @Test
    void v2_helper_via_chainAndNegRiskFlag() {
        // 通过 chainId + negRisk 派发，应等于直接传 verifyingContract
        byte[] viaFlag = EIP712OrderSigner.hashV2(fixtureOrder(), CHAIN_ID, false);
        byte[] direct = EIP712OrderSigner.hashV2(fixtureOrder(), CHAIN_ID, V2_EXCHANGE);
        assertThat(viaFlag).containsExactly(direct);

        byte[] viaFlagNr = EIP712OrderSigner.hashV2(fixtureOrder(), CHAIN_ID, true);
        byte[] directNr = EIP712OrderSigner.hashV2(fixtureOrder(), CHAIN_ID, V2_NEG_RISK_EXCHANGE);
        assertThat(viaFlagNr).containsExactly(directNr);
    }

    @Test
    void v2_signV2_returnsSignedOrderV2() {
        LocalSigner signer = LocalSigner.fromPrivateKey(PRIVATE_KEY);
        SignedOrderV2 signed = EIP712OrderSigner.signV2(signer, fixtureOrder(), CHAIN_ID, false).join();
        assertThat(signed.getSignature()).isEqualTo(
                "0x808bcc18d94bfe59daf619655ab83ff5701a82c0685255a7e3aeeeff7a7f8350"
                        + "1984f86f573ef685b749c11eeacc57ead0c148a58b5460ee4a1c45708a290c511c");
        assertThat(signed.getOrder()).isEqualTo(fixtureOrder());
    }
}
