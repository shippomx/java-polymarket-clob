package com.polymarket.clob.chain;

/**
 * EVM RPC 调用失败：JSON-RPC error 响应、HTTP 非 2xx、或 eth_call revert。
 * 运行时异常，由 CompletableFuture 透出。
 */
public class EvmRpcException extends RuntimeException {
    public EvmRpcException(String message) { super(message); }
    public EvmRpcException(String message, Throwable cause) { super(message, cause); }
}
