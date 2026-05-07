package com.polymarket.clob.example;

import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.chain.Web3jEvmRpcClient;
import com.polymarket.clob.deposit.DepositWalletDerivation;
import com.polymarket.clob.chain.DepositWalletReads;
import com.polymarket.clob.funxyz.DepositAddresses;
import com.polymarket.clob.funxyz.FunxyzClient;
import com.polymarket.clob.funxyz.FunxyzConfig;
import com.polymarket.clob.model.Address;

import java.net.URI;

/**
 * 端到端 demo：读 PK → 派生 Polymarket Deposit Wallet → 取 fun.xyz 四链入金地址 → 打印。
 *
 * <p>环境变量：
 * <ul>
 *   <li>{@code PK} —— EOA 私钥 hex（必填）</li>
 *   <li>{@code RPC_URL} —— Polygon JSON-RPC 端点（必填，用于派生 Deposit Wallet）</li>
 *   <li>{@code FUNXYZ_API_KEY} —— 自定义 fun.xyz key（可选；不设走默认 public key）</li>
 * </ul>
 *
 * <p>运行：{@code mvn compile exec:java -Dexec.mainClass=com.polymarket.clob.example.FunxyzAddressLookupExample}
 */
public final class FunxyzAddressLookupExample {

    private FunxyzAddressLookupExample() {}

    public static void main(String[] args) throws Exception {
        String pk = requireEnv("PK");
        String rpcUrl = requireEnv("RPC_URL");
        String funxyzKey = System.getenv("FUNXYZ_API_KEY");  // 可选

        Signer signer = LocalSigner.fromPrivateKeyHex(pk);
        Address eoa = signer.address();

        // 1. 派生 Polymarket Deposit Wallet（链上读)
        var rpc = new Web3jEvmRpcClient(URI.create(rpcUrl));
        var reads = new DepositWalletReads(rpc, 137L);
        Address wallet = new DepositWalletDerivation(reads).predictWalletAddress(eoa).get();

        // 2. 取 fun.xyz 入金地址
        FunxyzConfig.Builder cfgBuilder = FunxyzConfig.builder();
        if (funxyzKey != null && !funxyzKey.isBlank()) cfgBuilder.apiKey(funxyzKey);
        FunxyzClient client = new FunxyzClient(cfgBuilder.build());

        DepositAddresses addrs = client.getDepositAddresses(eoa, wallet).get();

        // 3. 打印
        System.out.println("EOA:             " + eoa);
        System.out.println("Deposit Wallet:  " + wallet);
        System.out.println();
        System.out.println("fun.xyz 入金地址（同一 EOA 永远固定）:");
        System.out.println("  EVM (Polygon):  " + addrs.evm());
        System.out.println("  Solana:         " + addrs.solana());
        System.out.println("  Tron:           " + addrs.tron());
        System.out.println("  BTC (segwit):   " + addrs.btcSegwit());
    }

    private static String requireEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("env var " + name + " not set");
        }
        return v;
    }
}
