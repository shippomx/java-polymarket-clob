package com.polymarket.clob.deposit;

import com.polymarket.clob.chain.DepositWalletConfig;
import com.polymarket.clob.chain.DepositWalletReads;
import com.polymarket.clob.model.Address;
import org.web3j.crypto.Hash;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 读 13 项 (token, kind, spender) 的链上现状，仅缺失项产出 Call。
 * 与 fullOnboardAndTrade.ts 行为一致：read-then-plan，不硬编码批次大小。
 */
public final class ApprovalPlanner {

    /** keccak256("approve(address,uint256)")[:4] */
    private static final byte[] SEL_APPROVE = selector("approve(address,uint256)");
    /** keccak256("setApprovalForAll(address,bool)")[:4] */
    private static final byte[] SEL_SET_APPROVAL_FOR_ALL = selector("setApprovalForAll(address,bool)");
    private static final BigInteger UINT256_MAX = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);

    private final DepositWalletReads reads;
    private final DepositWalletConfig cfg;

    public ApprovalPlanner(DepositWalletReads reads, DepositWalletConfig cfg) {
        this.reads = reads;
        this.cfg = cfg;
    }

    public CompletableFuture<List<Call>> planMissingApprovals(Address wallet) {
        List<ApprovalTargets.Entry> targets = ApprovalTargets.standard(cfg);
        List<CompletableFuture<Call>> probes = new ArrayList<>(targets.size());

        for (ApprovalTargets.Entry t : targets) {
            if (t.kind() == ApprovalTargets.Kind.ERC20) {
                probes.add(reads.erc20Allowance(t.token(), wallet, t.spender()).thenApply(cur ->
                        cur.compareTo(BigInteger.ZERO) > 0
                                ? null
                                : new Call(t.token(), BigInteger.ZERO, approveCalldata(t.spender()))));
            } else {
                probes.add(reads.ctfApprovedForAll(wallet, t.spender()).thenApply(cur ->
                        cur ? null
                            : new Call(t.token(), BigInteger.ZERO, setApprovalForAllCalldata(t.spender()))));
            }
        }

        return CompletableFuture.allOf(probes.toArray(new CompletableFuture[0]))
                .thenApply(v -> probes.stream()
                        .map(CompletableFuture::join)
                        .filter(c -> c != null)
                        .toList());
    }

    private static byte[] approveCalldata(Address spender) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 32 + 32);
        buf.put(SEL_APPROVE);
        buf.put(padAddress(spender));
        buf.put(padUint256(UINT256_MAX));
        return buf.array();
    }

    private static byte[] setApprovalForAllCalldata(Address operator) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 32 + 32);
        buf.put(SEL_SET_APPROVAL_FOR_ALL);
        buf.put(padAddress(operator));
        buf.put(padUint256(BigInteger.ONE)); // bool true = 1
        return buf.array();
    }

    private static byte[] selector(String sig) {
        return Arrays.copyOfRange(Hash.sha3(sig.getBytes(StandardCharsets.US_ASCII)), 0, 4);
    }

    private static byte[] padAddress(Address a) {
        byte[] out = new byte[32];
        System.arraycopy(a.toBytes(), 0, out, 12, 20);
        return out;
    }

    private static byte[] padUint256(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        if (raw.length > 32) System.arraycopy(raw, raw.length - 32, out, 0, 32);
        else System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }
}
