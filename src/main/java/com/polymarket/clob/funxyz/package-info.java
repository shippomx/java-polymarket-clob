/**
 * fun.xyz 法币入金提供商客户端。
 *
 * <h2>定位</h2>
 * Polymarket 网页端用 fun.xyz 把法币（信用卡 / Apple Pay / 银行转账，via Swapped/MoonPay）
 * 转成 Polygon USDC.e 打到用户的 Deposit Wallet。{@link com.polymarket.clob.funxyz.FunxyzClient}
 * 封装其中的 {@code POST /v1/eoa} 接口：给定 EOA + recipient，返回 fun.xyz 服务端为该 EOA 持久分配的
 * 多链入金中转地址。
 *
 * <h2>与 onboard/ deposit/ 的关系</h2>
 * 完全独立。本包不导入 {@code chain.*} / {@code deposit.*} / {@code onboard.*}，
 * 也不被它们导入。fun.xyz 是第三方服务，与链上 Deposit Wallet 流并列；调用方按需组合。
 *
 * <h2>线程安全</h2>
 * {@link com.polymarket.clob.funxyz.FunxyzClient} 实例不可变、线程安全。
 *
 * <h2>错误模型</h2>
 * 所有失败收敛到 {@link com.polymarket.clob.funxyz.FunxyzException}：
 * {@code httpStatus = -1} 表传输错，{@code isBlocked()} 表 fun.xyz 风控拒绝。
 *
 * @see com.polymarket.clob.funxyz.FunxyzClient
 */
package com.polymarket.clob.funxyz;
