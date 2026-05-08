package com.polymarket.clob.signing;

import com.polymarket.clob.api.model.Side;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.auth.UnsignedClobAuth;
import com.polymarket.clob.deposit.Call;
import com.polymarket.clob.deposit.UnsignedBatch;
import com.polymarket.clob.gamma.UnsignedSiwe;
import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.ChainId;
import com.polymarket.clob.order.OrderV2;
import com.polymarket.clob.order.UnsignedOrderV2Pol1271;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalSigningFacadeTest {

    private static final Address EOA    = Address.fromHex("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266");
    private static final Address WALLET = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");
    private static final String  ZERO32 = "0x" + "00".repeat(32);

    @Test
    void clobAuthFacadeMatchesDirectCall() {
        UnsignedClobAuth direct = UnsignedClobAuth.buildUnsigned(EOA, ChainId.AMOY, 1L, BigInteger.TEN);
        UnsignedClobAuth viaFacade = ExternalSigning.buildUnsignedClobAuth(EOA, ChainId.AMOY, 1L, BigInteger.TEN);
        assertThat(viaFacade.signingDigest32()).containsExactly(direct.signingDigest32());
    }

    @Test
    void batchFacadeMatchesDirectCall() {
        List<Call> calls = List.of(new Call(
                Address.fromHex("0x1111111111111111111111111111111111111111"),
                BigInteger.ZERO, new byte[]{1, 2, 3}));
        UnsignedBatch direct = UnsignedBatch.buildUnsigned(137L, EOA, WALLET, BigInteger.ONE, BigInteger.TEN, calls);
        UnsignedBatch viaFacade = ExternalSigning.buildUnsignedBatch(137L, EOA, WALLET, BigInteger.ONE, BigInteger.TEN, calls);
        assertThat(viaFacade.signingDigest32()).containsExactly(direct.signingDigest32());
    }

    @Test
    void siweFacadeMatchesDirectCall() {
        Instant a = Instant.parse("2026-01-01T00:00:00Z");
        Instant b = Instant.parse("2026-01-08T00:00:00Z");
        UnsignedSiwe direct = UnsignedSiwe.buildUnsigned(EOA, ChainId.POLYGON, "n", a, b);
        UnsignedSiwe viaFacade = ExternalSigning.buildUnsignedSiwe(EOA, ChainId.POLYGON, "n", a, b);
        assertThat(viaFacade.signingDigest32()).containsExactly(direct.signingDigest32());
    }

    @Test
    void orderV2Pol1271FacadeMatchesDirectCall() {
        OrderV2 o = OrderV2.builder()
                .salt(BigInteger.valueOf(761396384028L))
                .maker(WALLET).signer(WALLET)
                .tokenId(new BigInteger("57597306756265660"))
                .makerAmount(BigInteger.valueOf(1_000_000))
                .takerAmount(BigInteger.valueOf(1_886_700))
                .side(Side.BUY).signatureType(SignatureType.POLY_1271)
                .timestamp(BigInteger.valueOf(1778079826561L))
                .expiration(BigInteger.ZERO).metadata(ZERO32).builder(ZERO32)
                .build();
        UnsignedOrderV2Pol1271 direct = UnsignedOrderV2Pol1271.buildUnsigned(o, 137L, false);
        UnsignedOrderV2Pol1271 viaFacade = ExternalSigning.buildUnsignedOrderV2Pol1271(o, 137L, false);
        assertThat(viaFacade.signingDigest32()).containsExactly(direct.signingDigest32());
    }
}
