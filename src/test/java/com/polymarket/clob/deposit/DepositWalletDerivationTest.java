package com.polymarket.clob.deposit;

import com.polymarket.clob.chain.DepositWalletReads;
import com.polymarket.clob.chain.EvmRpcClient;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class DepositWalletDerivationTest {

    @Test
    void deriveDelegatesToReads() throws Exception {
        EvmRpcClient stub = new EvmRpcClient() {
            @Override public CompletableFuture<byte[]> ethCall(Address to, byte[] callData) {
                return CompletableFuture.completedFuture(HexFormat.of().parseHex(
                        "000000000000000000000000ada4563a6738215c56d2b59bc1c5a1db65b1fd78"));
            }
            @Override public CompletableFuture<byte[]> getCode(Address addr) {
                return CompletableFuture.completedFuture(new byte[]{0x60});
            }
        };
        DepositWalletDerivation d = new DepositWalletDerivation(new DepositWalletReads(stub, 137));

        Address w = d.predictWalletAddress(
                Address.fromHex("0x4cAfCf2D9A032f57088872f6546Bb68305d209D7")).get();
        assertThat(w).isEqualTo(Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78"));
        assertThat(d.isDeployed(w).get()).isTrue();
    }
}
