package com.polymarket.clob.model;

/**
 * EVM chain id. Polymarket 目前支持 Polygon 主网 (137) 和 Amoy 测试网 (80002)。
 * 使用 {@code long} 原始包装是因为 Web3j 签名 API 直接接受 {@code long}。
 */
public final class ChainId {
    public static final long POLYGON = 137L;
    public static final long AMOY = 80002L;

    private ChainId() {}
}
