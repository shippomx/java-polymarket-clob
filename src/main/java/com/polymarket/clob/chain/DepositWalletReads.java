package com.polymarket.clob.chain;

import com.polymarket.clob.model.Address;
import org.web3j.crypto.Hash;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

/**
 * Deposit Wallet onboarding 期间需要做的 6 类合约只读调用。封装 ABI 编解码细节。
 */
public class DepositWalletReads {

    /** keccak256("predictWalletAddress(address,bytes32)")[:4] */
    private static final byte[] SEL_PREDICT_WALLET = selector("predictWalletAddress(address,bytes32)");
    /** keccak256("nonce()")[:4] */
    private static final byte[] SEL_NONCE          = selector("nonce()");
    /** keccak256("allowance(address,address)")[:4] */
    private static final byte[] SEL_ALLOWANCE      = selector("allowance(address,address)");
    /** keccak256("isApprovedForAll(address,address)")[:4] */
    private static final byte[] SEL_APPROVED_ALL   = selector("isApprovedForAll(address,address)");
    /** keccak256("balanceOf(address)")[:4] */
    private static final byte[] SEL_BALANCE_OF     = selector("balanceOf(address)");

    private final EvmRpcClient rpc;
    private final long chainId;

    public DepositWalletReads(EvmRpcClient rpc, long chainId) {
        this.rpc = rpc;
        this.chainId = chainId;
    }

    public CompletableFuture<Address> predictWalletAddress(Address eoa) {
        DepositWalletConfig cfg = require();
        byte[] data = concat(SEL_PREDICT_WALLET, padAddress(cfg.implementation()), padAddress(eoa));
        return rpc.ethCall(cfg.factory(), data).thenApply(DepositWalletReads::decodeAddress);
    }

    public CompletableFuture<Boolean> isDeployed(Address wallet) {
        return rpc.getCode(wallet).thenApply(code -> code != null && code.length > 0);
    }

    public CompletableFuture<BigInteger> walletNonce(Address wallet) {
        return rpc.ethCall(wallet, SEL_NONCE).thenApply(DepositWalletReads::decodeUint256);
    }

    public CompletableFuture<BigInteger> erc20Allowance(Address token, Address owner, Address spender) {
        byte[] data = concat(SEL_ALLOWANCE, padAddress(owner), padAddress(spender));
        return rpc.ethCall(token, data).thenApply(DepositWalletReads::decodeUint256);
    }

    public CompletableFuture<Boolean> ctfApprovedForAll(Address owner, Address operator) {
        DepositWalletConfig cfg = require();
        byte[] data = concat(SEL_APPROVED_ALL, padAddress(owner), padAddress(operator));
        return rpc.ethCall(cfg.ctf(), data).thenApply(DepositWalletReads::decodeBool);
    }

    public CompletableFuture<BigInteger> erc20BalanceOf(Address token, Address owner) {
        byte[] data = concat(SEL_BALANCE_OF, padAddress(owner));
        return rpc.ethCall(token, data).thenApply(DepositWalletReads::decodeUint256);
    }

    private DepositWalletConfig require() {
        return com.polymarket.clob.model.ContractRegistry.depositWalletConfig(chainId)
                .orElseThrow(() -> new EvmRpcException("DepositWallet not deployed on chainId " + chainId));
    }

    // ---- ABI helpers ----

    private static byte[] selector(String signature) {
        byte[] full = Hash.sha3(signature.getBytes(StandardCharsets.US_ASCII));
        return Arrays.copyOfRange(full, 0, 4);
    }

    private static byte[] padAddress(Address a) {
        byte[] out = new byte[32];
        System.arraycopy(a.toBytes(), 0, out, 12, 20);
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    private static Address decodeAddress(byte[] returnData) {
        if (returnData == null || returnData.length != 32) {
            throw new EvmRpcException("expected exactly 32B address return, got "
                    + (returnData == null ? -1 : returnData.length));
        }
        byte[] addr = Arrays.copyOfRange(returnData, 12, 32);
        return Address.fromBytes(addr);
    }

    private static BigInteger decodeUint256(byte[] returnData) {
        if (returnData == null || returnData.length != 32) {
            throw new EvmRpcException("expected exactly 32B uint256 return, got "
                    + (returnData == null ? -1 : returnData.length));
        }
        return new BigInteger(1, returnData);
    }

    private static boolean decodeBool(byte[] returnData) {
        return decodeUint256(returnData).signum() != 0;
    }
}
