package com.polymarket.clob.deposit;

import com.polymarket.clob.chain.DepositWalletConfig;
import com.polymarket.clob.chain.DepositWalletReads;
import com.polymarket.clob.chain.EvmRpcClient;
import com.polymarket.clob.chain.PolymarketContracts;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalPlannerTest {

    private static final Address WALLET = Address.fromHex("0xada4563A6738215c56D2B59BC1C5a1dB65b1fD78");

    @Test
    void allMaxedReturnsEmpty() throws Exception {
        BigInteger MAX = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
        DepositWalletReads reads = new StubReads().withErc20All(MAX).withCtfAll(true);

        List<Call> calls = new ApprovalPlanner(reads, DepositWalletConfig.POLYGON)
                .planMissingApprovals(WALLET).get();

        assertThat(calls).isEmpty();
    }

    @Test
    void allZeroEmits13Calls() throws Exception {
        DepositWalletReads reads = new StubReads().withErc20All(BigInteger.ZERO).withCtfAll(false);

        List<Call> calls = new ApprovalPlanner(reads, DepositWalletConfig.POLYGON)
                .planMissingApprovals(WALLET).get();

        assertThat(calls).hasSize(13);
        // 第 1 项 = USDC.e approve(CTF, MAX)
        Call first = calls.get(0);
        assertThat(first.target()).isEqualTo(PolymarketContracts.USDC_E);
        // selector keccak256("approve(address,uint256)")[:4] = 0x095ea7b3
        assertThat(HexFormat.of().formatHex(first.data())).startsWith("095ea7b3");
        // 第 8 项 = CTF setApprovalForAll(EXCHANGE_V2, true) — selector 0xa22cb465
        Call eighth = calls.get(7);
        assertThat(eighth.target()).isEqualTo(PolymarketContracts.CTF);
        assertThat(HexFormat.of().formatHex(eighth.data())).startsWith("a22cb465");
    }

    @Test
    void mixedStateEmitsOnlyMissing() throws Exception {
        StubReads reads = new StubReads();
        BigInteger MAX = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
        // 让前 4 个 ERC20 已 max，剩下需要 approve
        reads.allowanceLookup = (token, owner, spender) ->
                spender.equals(PolymarketContracts.CTF)
                        || spender.equals(PolymarketContracts.EXCHANGE_V2)
                        || spender.equals(PolymarketContracts.NEG_RISK_EXCHANGE_V2)
                        || spender.equals(PolymarketContracts.NEG_RISK_ADAPTER)
                        ? MAX : BigInteger.ZERO;
        // CTF approval 全无
        reads.ctfApprovedLookup = (owner, op) -> false;

        List<Call> calls = new ApprovalPlanner(reads, DepositWalletConfig.POLYGON)
                .planMissingApprovals(WALLET).get();

        // 13 - 4 = 9
        assertThat(calls).hasSize(9);
    }

    /** 仿真 reads，可注入 lookup lambda。 */
    static class StubReads extends DepositWalletReads {
        @FunctionalInterface
        interface AllowanceFn { BigInteger get(Address token, Address owner, Address spender); }
        @FunctionalInterface
        interface CtfFn       { boolean get(Address owner, Address operator); }

        AllowanceFn allowanceLookup = (t, o, s) -> BigInteger.ZERO;
        CtfFn       ctfApprovedLookup = (o, s) -> false;

        StubReads() {
            super(new EvmRpcClient() {
                @Override public CompletableFuture<byte[]> ethCall(Address to, byte[] callData) {
                    return CompletableFuture.failedFuture(new UnsupportedOperationException("stub"));
                }
                @Override public CompletableFuture<byte[]> getCode(Address addr) {
                    return CompletableFuture.failedFuture(new UnsupportedOperationException("stub"));
                }
            }, 137);
        }

        StubReads withErc20All(BigInteger v) {
            allowanceLookup = (t, o, s) -> v;
            return this;
        }

        StubReads withCtfAll(boolean v) {
            ctfApprovedLookup = (o, s) -> v;
            return this;
        }

        @Override
        public CompletableFuture<BigInteger> erc20Allowance(Address token, Address owner, Address spender) {
            return CompletableFuture.completedFuture(allowanceLookup.get(token, owner, spender));
        }

        @Override
        public CompletableFuture<Boolean> ctfApprovedForAll(Address owner, Address operator) {
            return CompletableFuture.completedFuture(ctfApprovedLookup.get(owner, operator));
        }
    }
}
