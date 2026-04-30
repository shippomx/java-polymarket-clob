package com.polymarket.clob.gasless;

/**
 * Relayer 事务最终（或中间）状态。
 *
 * <p>容忍上游字段命名漂移：{@code transactionID/transactionId}、{@code state/STATE_*}、
 * {@code transactionHash/hash}、{@code errorMsg/error/reason/...} 在解析时被规范化到
 * 本 record 的 4 个字段。</p>
 *
 * @param txId   relayer 分配的 transaction id
 * @param state  规范化后的状态字符串：{@code CONFIRMED} / {@code FAILED} / {@code INVALID} 等
 * @param txHash 终态时的链上 tx hash（中间态可能为 {@code null}）
 * @param error  失败时的错误描述（成功时为 {@code null}）
 */
public record RelayerTxResult(String txId, String state, String txHash, String error) {

    /** 已经达到 relayer 的终态，无论成功失败。 */
    public boolean isTerminal() {
        return state != null
                && ("CONFIRMED".equals(state) || "FAILED".equals(state) || "INVALID".equals(state));
    }

    /** 终态且成功（{@code CONFIRMED} 或 {@code MINED}）。 */
    public boolean isSuccess() {
        return state != null && ("CONFIRMED".equals(state) || "MINED".equals(state));
    }
}
