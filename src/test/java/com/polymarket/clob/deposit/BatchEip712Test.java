package com.polymarket.clob.deposit;

import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BatchEip712Test {

    private static final Address WALLET = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");

    @Test
    void digestIs32Bytes() {
        byte[] digest = BatchEip712.hashBatch(
                137, WALLET, BigInteger.ZERO, BigInteger.valueOf(1778081214L),
                List.of(new Call(PolymarketContracts.USDC_E, BigInteger.ZERO, new byte[]{0x01, 0x02})));
        assertThat(digest).hasSize(32);
    }

    @Test
    void digestIsDeterministic() {
        byte[] d1 = BatchEip712.hashBatch(137, WALLET, BigInteger.ZERO, BigInteger.valueOf(100),
                List.of(new Call(PolymarketContracts.USDC_E, BigInteger.ZERO, new byte[]{0x01})));
        byte[] d2 = BatchEip712.hashBatch(137, WALLET, BigInteger.ZERO, BigInteger.valueOf(100),
                List.of(new Call(PolymarketContracts.USDC_E, BigInteger.ZERO, new byte[]{0x01})));
        assertThat(d1).containsExactly(d2);
    }

    @Test
    void digestChangesWithNonce() {
        Call c = new Call(PolymarketContracts.USDC_E, BigInteger.ZERO, new byte[]{0x01});
        byte[] d1 = BatchEip712.hashBatch(137, WALLET, BigInteger.ZERO, BigInteger.valueOf(100), List.of(c));
        byte[] d2 = BatchEip712.hashBatch(137, WALLET, BigInteger.ONE,  BigInteger.valueOf(100), List.of(c));
        assertThat(d1).isNotEqualTo(d2);
    }

    @Test
    void digestChangesWithDeadline() {
        Call c = new Call(PolymarketContracts.USDC_E, BigInteger.ZERO, new byte[]{0x01});
        byte[] d1 = BatchEip712.hashBatch(137, WALLET, BigInteger.ZERO, BigInteger.valueOf(100), List.of(c));
        byte[] d2 = BatchEip712.hashBatch(137, WALLET, BigInteger.ZERO, BigInteger.valueOf(200), List.of(c));
        assertThat(d1).isNotEqualTo(d2);
    }

    @Test
    void digestChangesWithCallData() {
        byte[] d1 = BatchEip712.hashBatch(137, WALLET, BigInteger.ZERO, BigInteger.valueOf(100),
                List.of(new Call(PolymarketContracts.USDC_E, BigInteger.ZERO, new byte[]{0x01})));
        byte[] d2 = BatchEip712.hashBatch(137, WALLET, BigInteger.ZERO, BigInteger.valueOf(100),
                List.of(new Call(PolymarketContracts.USDC_E, BigInteger.ZERO, new byte[]{0x02})));
        assertThat(d1).isNotEqualTo(d2);
    }

    @Test
    void emptyCallsRejected() {
        assertThatThrownBy(() -> BatchEip712.hashBatch(
                137, WALLET, BigInteger.ZERO, BigInteger.valueOf(100), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
