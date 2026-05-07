package com.polymarket.clob.chain;

import com.polymarket.clob.model.Address;

import java.util.concurrent.CompletableFuture;

/**
 * 极简 EVM JSON-RPC 客户端。本接口刻意只暴露两个方法 —— {@code eth_call} 与
 * {@code eth_getCode}，让 RPC 提供方与业务读取层解耦。具体的 6 类合约读取在
 * {@link DepositWalletReads} 里做 ABI 编解码。
 */
public interface EvmRpcClient {

    /**
     * 执行 eth_call。
     *
     * @return 返回字节序列；调用方负责按预期 ABI 类型解码
     * @throws EvmRpcException 若 JSON-RPC error、HTTP 非 2xx 或 eth_call revert
     */
    CompletableFuture<byte[]> ethCall(Address to, byte[] callData);

    /**
     * 执行 eth_getCode。未部署地址 RPC 返回 "0x"，本方法返回空字节数组。
     */
    CompletableFuture<byte[]> getCode(Address addr);
}
