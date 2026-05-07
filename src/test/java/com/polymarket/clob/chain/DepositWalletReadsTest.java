package com.polymarket.clob.chain;

import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        rpc.willReturnCall(HexFormat.of().parseHex(
                "000000000000000000000000ada4563a6738215c56d2b59bc1c5a1db65b1fd78"));

        Address result = new DepositWalletReads(rpc, 137).predictWalletAddress(EOA).get();

        assertThat(result).isEqualTo(WALLET);

        byte[] calldata = rpc.lastCall();
        assertThat(calldata).hasSize(68);

        // 0..4 = selector keccak256("predictWalletAddress(address,bytes32)")[:4]
        byte[] selector = java.util.Arrays.copyOfRange(calldata, 0, 4);
        byte[] expectedSelector = java.util.Arrays.copyOfRange(
                org.web3j.crypto.Hash.sha3(
                        "predictWalletAddress(address,bytes32)".getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
                0, 4);
        assertThat(selector).containsExactly(expectedSelector);

        // 4..36 = padded IMPLEMENTATION (first arg)
        byte[] implArg = java.util.Arrays.copyOfRange(calldata, 4, 36);
        byte[] expectedImpl = new byte[32];
        System.arraycopy(PolymarketContracts.IMPLEMENTATION.toBytes(), 0, expectedImpl, 12, 20);
        assertThat(implArg).containsExactly(expectedImpl);

        // 36..68 = padded EOA (second arg, bytes32 slot)
        byte[] eoaArg = java.util.Arrays.copyOfRange(calldata, 36, 68);
        byte[] expectedEoa = new byte[32];
        System.arraycopy(EOA.toBytes(), 0, expectedEoa, 12, 20);
        assertThat(eoaArg).containsExactly(expectedEoa);
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
    void unsupportedChainIdThrows() {
        StubRpc rpc = new StubRpc();
        DepositWalletReads reads = new DepositWalletReads(rpc, 1);  // mainnet, not Polygon
        assertThatThrownBy(() -> reads.predictWalletAddress(EOA).get())
                .isInstanceOfAny(EvmRpcException.class, java.util.concurrent.ExecutionException.class)
                .hasMessageContaining("chainId 1");
    }

    @Test
    void erc20AllowanceEncodesOwnerThenSpender() throws Exception {
        StubRpc rpc = new StubRpc();
        rpc.willReturnCall(HexFormat.of().parseHex(
                "0000000000000000000000000000000000000000000000000000000000000000"));

        new DepositWalletReads(rpc, 137)
                .erc20Allowance(PolymarketContracts.USDC_E, WALLET, PolymarketContracts.CTF).get();

        byte[] calldata = rpc.lastCall();
        // 4..36 = owner (WALLET)
        byte[] ownerArg = java.util.Arrays.copyOfRange(calldata, 4, 36);
        byte[] expectedOwner = new byte[32];
        System.arraycopy(WALLET.toBytes(), 0, expectedOwner, 12, 20);
        assertThat(ownerArg).containsExactly(expectedOwner);
        // 36..68 = spender (CTF)
        byte[] spenderArg = java.util.Arrays.copyOfRange(calldata, 36, 68);
        byte[] expectedSpender = new byte[32];
        System.arraycopy(PolymarketContracts.CTF.toBytes(), 0, expectedSpender, 12, 20);
        assertThat(spenderArg).containsExactly(expectedSpender);
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
