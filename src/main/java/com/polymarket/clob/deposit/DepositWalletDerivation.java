package com.polymarket.clob.deposit;

import com.polymarket.clob.chain.DepositWalletReads;
import com.polymarket.clob.model.Address;

import java.util.concurrent.CompletableFuture;

/**
 * 薄外观 —— 把 EOA → wallet 派生与部署检测两个最常用动作集中暴露。
 * 内部仅委托 {@link DepositWalletReads}；保留独立类是为编排器代码可读。
 */
public final class DepositWalletDerivation {

    private final DepositWalletReads reads;

    public DepositWalletDerivation(DepositWalletReads reads) {
        this.reads = reads;
    }

    public CompletableFuture<Address> predictWalletAddress(Address eoa) {
        return reads.predictWalletAddress(eoa);
    }

    public CompletableFuture<Boolean> isDeployed(Address wallet) {
        return reads.isDeployed(wallet);
    }
}
