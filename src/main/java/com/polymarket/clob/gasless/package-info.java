/**
 * Polymarket gasless 链上流水线公开 API。
 *
 * <p>本包提供从 EOA 到 Safe 钱包的全套 gasless 操作能力，所有链上动作都通过
 * {@link com.polymarket.clob.gasless.GaslessRelayer} 委托给 Polymarket Builder-Relayer
 * （{@code https://relayer-v2.polymarket.com}）执行，EOA 不需要持有 MATIC：</p>
 *
 * <ul>
 *   <li><b>{@link com.polymarket.clob.gasless.GaslessRelayer}</b> —— 主入口；提供
 *       <ul>
 *         <li><b>本地私钥模式</b>（{@code deploy} / {@code execute} / {@code setupApprovals}），
 *             适合 example、单进程脚本；</li>
 *         <li><b>BE-App 协议模式</b>（{@code prepareDeploy} / {@code prepareExecute} /
 *             {@code prepareApprovals} + {@code submit}），适合 BE 服务把签名委托给 App 钱包。</li>
 *       </ul>
 *   </li>
 *   <li><b>{@link com.polymarket.clob.gasless.SafeEip712}</b> —— Safe v1.3.0
 *       {@code execTransaction} 与 SafeProxyFactory {@code createProxy} 的 EIP-712 摘要计算
 *       （手工字节级实现，对齐 npm builder-relayer-client v0.0.6 与 Rust SDK）。</li>
 *   <li><b>{@link com.polymarket.clob.gasless.SafeSignatures}</b> —— ECDSA 签名后处理：
 *       SafeTx 路径的 {@code v += 4} 调整、SAFE-CREATE 路径的直签 v 归一、yParity 0/1 → 27/28
 *       归一。</li>
 *   <li><b>{@link com.polymarket.clob.gasless.MultiSend}</b> —— Gnosis MultiSend payload 与
 *       {@code multiSend(bytes)} ABI 包装。</li>
 *   <li><b>{@link com.polymarket.clob.gasless.Calldata}</b> —— 业务侧常用 calldata 工厂：
 *       ERC20 {@code approve} / {@code transfer}、ERC1155 {@code setApprovalForAll}、
 *       ConditionalTokens {@code redeemPositions}，以及 6 笔标准授权批次的业务编排。</li>
 *   <li><b>{@link com.polymarket.clob.gasless.RelayerTx} / {@link com.polymarket.clob.gasless.SafeTxPayload}
 *       / {@link com.polymarket.clob.gasless.SafeTxStyle} / {@link com.polymarket.clob.gasless.RelayerTxResult}</b>
 *       —— 数据类型，承载业务到 wire 的中间表示。</li>
 * </ul>
 *
 * <p>本包是 SDK 公开稳定接口，非破坏性变更前不会动签名；如需扩展更多 selector，请加在
 * {@link com.polymarket.clob.gasless.Calldata} 内。</p>
 */
package com.polymarket.clob.gasless;
