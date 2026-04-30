package com.polymarket.clob.gasless;

import com.polymarket.clob.model.Address;

import java.math.BigInteger;
import java.util.Objects;

/**
 * 一笔已经计算好 {@code safeTxHash} 但<b>尚未签名</b>的 relayer 提交材料。
 *
 * <p>本类型存在的唯一目的是让"摘要计算 + wire 序列化"与"签名"解耦——给 BE-App 协议路径用：
 * BE 服务调 {@link GaslessRelayer#prepareDeploy} / {@link GaslessRelayer#prepareExecute} /
 * {@link GaslessRelayer#prepareApprovals} 拿到本结构 → 把 32 字节 {@link #safeTxHash} 发给
 * App 钱包按 {@link #style} 指定的形态签 → BE 调 {@link GaslessRelayer#submit} 把 sig 喂
 * 回来，由 SDK 完成 wire 拼装与 HTTP 提交。</p>
 *
 * <p>本地私钥的"全自动"路径（{@link GaslessRelayer#deploy} / {@link GaslessRelayer#execute}）
 * 内部仍是这同一条数据流：先 {@code prepare*}，再用本进程私钥签，再 {@code submit}。两条路径
 * 对 relayer 而言是同一个 wire 包。</p>
 *
 * @param wireType    relayer 提交字段 {@code "type"}：{@code "SAFE"} 或 {@code "SAFE-CREATE"}
 * @param eoa         EOA owner 地址（提交字段 {@code "from"}）
 * @param safe        Safe 钱包地址（提交字段 {@code "proxyWallet"}）
 * @param to          被调对象（execTransaction 的 target；SAFE-CREATE 时是 SafeFactory）
 * @param data        ABI calldata；SAFE-CREATE 时为 {@code 0x}
 * @param operation   {@code 0=Call} / {@code 1=DelegateCall}；SAFE-CREATE 路径忽略
 * @param nonce       Safe 当前 nonce；SAFE-CREATE 路径忽略（factory 自管 nonce）
 * @param safeTxHash  32 字节 EIP-712 final hash —— 这是 App 端要签的<b>唯一</b>输入
 * @param style       {@link SafeTxStyle#SAFE_TX_PERSONAL_SIGN} 或
 *                    {@link SafeTxStyle#SAFE_CREATE_DIRECT}，决定签名路径
 * @param description {@code "metadata"} 字段（仅 SAFE 路径出现在 wire 上，SAFE-CREATE 忽略）
 */
public record SafeTxPayload(
        String wireType,
        Address eoa,
        Address safe,
        Address to,
        byte[] data,
        int operation,
        BigInteger nonce,
        byte[] safeTxHash,
        SafeTxStyle style,
        String description) {

    public SafeTxPayload {
        Objects.requireNonNull(wireType, "wireType");
        Objects.requireNonNull(eoa, "eoa");
        Objects.requireNonNull(safe, "safe");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(safeTxHash, "safeTxHash");
        Objects.requireNonNull(style, "style");
        if (safeTxHash.length != 32) {
            throw new IllegalArgumentException("safeTxHash 必须 32 字节，实际 " + safeTxHash.length);
        }
        if (operation != 0 && operation != 1) {
            throw new IllegalArgumentException("operation 必须 0=Call 或 1=DelegateCall：" + operation);
        }
        if (nonce == null) {
            nonce = BigInteger.ZERO;
        }
        if (description == null) {
            description = "";
        }
    }

    /** SAFE-CREATE 路径的便捷判定，对应 wireType 为 {@code "SAFE-CREATE"}。 */
    public boolean isSafeCreate() {
        return style == SafeTxStyle.SAFE_CREATE_DIRECT;
    }
}
