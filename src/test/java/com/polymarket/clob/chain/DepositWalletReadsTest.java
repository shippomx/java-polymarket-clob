package com.polymarket.clob.chain;

import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DepositWalletReadsTest {

    private static final Address EOA    = Address.fromHex("0x4cAfCf2D9A032f57088872f6546Bb68305d209D7");
    private static final Address WALLET = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");

    /** 仿真：按提交的 calldata 4-byte selector 决定返回什么。 */
    private static final class StubRpc implements EvmRpcClient {
        private final List<byte[]> calls = new java.util.ArrayList<>();
        private final AtomicReference<byte[]> nextCallResult = new AtomicReference<>();
        private final AtomicReference<byte[]> nextCodeResult = new AtomicReference<>();

        public byte[] lastCall() { return calls.get(calls.size() - 1); }

        public void willReturnCall(byte[] r) { nextCallResult.set(r); }
        public void willReturnCode(byte[] r) { nextCodeResult.set(r); }

        @Override
        public CompletableFuture<byte[]> ethCall(Address to, byte[] callData) {
            calls.add(callData);
            return CompletableFuture.completedFuture(nextCallResult.get());
        }

        @Override
        public CompletableFuture<byte[]> getCode(Address addr) {
            return CompletableFuture.completedFuture(nextCodeResult.get());
        }
    }

    @Test
    void predictWalletAddressEncodesSelectorAndArgs() throws Exception {
        StubRpc rpc = new StubRpc();
        // 32B 返回值 = padded wallet 地址
        rpc.willReturnCall(HexFormat.of().parseHex(
                "000000000000000000000000ada4563a6738215c56d2b59bc1c5a1db65b1fd78"));

        Address result = new DepositWalletReads(rpc, 137).predictWalletAddress(EOA).get();

        assertThat(result).isEqualTo(WALLET);
        // selector keccak256("predictWalletAddress(address,bytes32)")[:4] = 0x...
        // 验证 calldata 长度 = 4 + 32 + 32 = 68
        assertThat(rpc.lastCall()).hasSize(68);
        // 后 32 字节 = pad32(EOA) = 12 个 0 + 20B EOA
        byte[] eoaArg = java.util.Arrays.copyOfRange(rpc.lastCall(), 36, 68);
        byte[] expectedPad = new byte[32];
        System.arraycopy(EOA.toBytes(), 0, expectedPad, 12, 20);
        assertThat(eoaArg).containsExactly(expectedPad);
    }

    @Test
    void isDeployedTrueWhenCodePresent() throws Exception {
        StubRpc rpc = new StubRpc();
        rpc.willReturnCode(new byte[]{0x60, (byte) 0x80});
        assertThat(new DepositWalletReads(rpc, 137).isDeployed(WALLET).get()).isTrue();
    }

    @Test
    void isDeployedFalseWhenEmpty() throws Exception {
        StubRpc rpc = new StubRpc();
        rpc.willReturnCode(new byte[0]);
        assertThat(new DepositWalletReads(rpc, 137).isDeployed(WALLET).get()).isFalse();
    }

    @Test
    void walletNonceDecodesUint256() throws Exception {
        StubRpc rpc = new StubRpc();
        // nonce = 5
        rpc.willReturnCall(HexFormat.of().parseHex(
                "0000000000000000000000000000000000000000000000000000000000000005"));
        assertThat(new DepositWalletReads(rpc, 137).walletNonce(WALLET).get())
                .isEqualTo(BigInteger.valueOf(5));
    }

    @Test
    void erc20AllowanceDecodesUint256() throws Exception {
        StubRpc rpc = new StubRpc();
        // allowance = 1_000_000
        rpc.willReturnCall(HexFormat.of().parseHex(
                "00000000000000000000000000000000000000000000000000000000000f4240"));
        BigInteger r = new DepositWalletReads(rpc, 137)
                .erc20Allowance(PolymarketContracts.USDC_E, WALLET, PolymarketContracts.CTF).get();
        assertThat(r).isEqualTo(BigInteger.valueOf(1_000_000));
    }

    @Test
    void ctfApprovedForAllReturnsBoolean() throws Exception {
        StubRpc rpc = new StubRpc();
        rpc.willReturnCall(HexFormat.of().parseHex(
                "0000000000000000000000000000000000000000000000000000000000000001"));
        assertThat(new DepositWalletReads(rpc, 137)
                .ctfApprovedForAll(WALLET, PolymarketContracts.EXCHANGE_V2).get()).isTrue();

        rpc.willReturnCall(HexFormat.of().parseHex(
                "0000000000000000000000000000000000000000000000000000000000000000"));
        assertThat(new DepositWalletReads(rpc, 137)
                .ctfApprovedForAll(WALLET, PolymarketContracts.EXCHANGE_V2).get()).isFalse();
    }

    @Test
    void erc20BalanceOfDecodesUint256() throws Exception {
        StubRpc rpc = new StubRpc();
        rpc.willReturnCall(HexFormat.of().parseHex(
                "00000000000000000000000000000000000000000000000000000000000186a0"));
        assertThat(new DepositWalletReads(rpc, 137)
                .erc20BalanceOf(PolymarketContracts.USDC_E, WALLET).get())
                .isEqualTo(BigInteger.valueOf(100_000));
    }
}
