package com.polymarket.clob.deposit;

/** Relayer 提交时已知字段（不含签名）。 */
public record RelayerTx(String type, String from, String to, String txnId) {}
