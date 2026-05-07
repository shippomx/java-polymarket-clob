package com.polymarket.clob.deposit;

import java.util.Optional;

/**
 * /transaction 与 /submit 共享的事务状态。
 * state 取自 Polymarket relayer：NEW / EXECUTED / CONFIRMED / FAILED / INVALID（去掉 STATE_ 前缀）。
 */
public record RelayerTxResult(String txId, String state, String txHash, String error) {

    public boolean isTerminal() {
        return "CONFIRMED".equals(state) || "FAILED".equals(state) || "INVALID".equals(state);
    }

    public boolean isConfirmed() { return "CONFIRMED".equals(state); }

    public Optional<String> errorOpt() {
        return error == null || error.isBlank() ? Optional.empty() : Optional.of(error);
    }
}
