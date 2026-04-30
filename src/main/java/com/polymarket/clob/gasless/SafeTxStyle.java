package com.polymarket.clob.gasless;

/**
 * Polymarket relayer 的 Safe 签名风格。
 *
 * <p>同一个 65 字节 ECDSA 输出，根据被调用对象（SafeFactory / Safe execTransaction）
 * 走的合约校验路径不同，{@code v} 字段需要做不同的 magic 调整：</p>
 *
 * <ol>
 *   <li>{@link #SAFE_TX_PERSONAL_SIGN} —— Safe v1.3.0 {@code execTransaction}（含 6 笔授权批次、
 *       Redeem、Withdraw Direct、出金 SafeTx）。EOA 需要先做 {@code personal_sign}：
 *       {@code keccak256("\x19Ethereum Signed Message:\n32" || safeTxHash)}，对其签名得到
 *       v=27/28；提交 relayer 前 {@code v += 4} → 31/32，Safe 合约据此走 {@code eth_sign}
 *       校验路径。</li>
 *   <li>{@link #SAFE_CREATE_DIRECT} —— SafeProxyFactory 的 {@code createProxy} 部署。EOA 直接
 *       对 EIP-712 final hash 做 ECDSA，v 保持 27/28，不再做 personal_sign 包裹也不 +4。
 *       SafeFactory 在 {@code createProxy} 时是用裸 EIP-712 sig 跑 {@code ecrecover}。</li>
 * </ol>
 *
 * <p><b>App 端钱包侧的对应动作</b>：</p>
 * <ul>
 *   <li>{@link #SAFE_TX_PERSONAL_SIGN} → 必须用 {@code personal_sign}（不是 typed-data sign）；</li>
 *   <li>{@link #SAFE_CREATE_DIRECT} → 必须用 {@code signTypedData_v4(... "CreateProxy")}（不是
 *       personal_sign）。</li>
 * </ul>
 * 任何一边走错路径都会让 ecrecover 校验失败，且签名长度合法因此不会被早期校验拦下，是典型的
 * "沉默错误"——必须在 BE-App 协议层显式约定。
 */
public enum SafeTxStyle {

    /** Safe execTransaction 路径：personal_sign 包裹 + v += 4 → 31/32。 */
    SAFE_TX_PERSONAL_SIGN,

    /** Safe-Factory createProxy 路径：直签 EIP-712 摘要，v 保持 27/28。 */
    SAFE_CREATE_DIRECT
}
