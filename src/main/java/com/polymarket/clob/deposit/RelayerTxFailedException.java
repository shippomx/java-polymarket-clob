package com.polymarket.clob.deposit;

/** Relayer 事务最终态为 FAILED / INVALID。 */
public class RelayerTxFailedException extends RuntimeException {
    public RelayerTxFailedException(RelayerTxResult result) {
        super("relayer tx " + result.txId() + " state=" + result.state()
                + " hash=" + result.txHash() + " error=" + result.error());
    }
}
