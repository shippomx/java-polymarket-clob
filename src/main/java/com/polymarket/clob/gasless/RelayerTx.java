package com.polymarket.clob.gasless;

import com.polymarket.clob.model.Address;

import java.math.BigInteger;
import java.util.Objects;

/**
 * 一笔需要由 Polymarket Safe 通过 {@code execTransaction} 执行的子事务。
 *
 * <p>本结构是 SDK gasless 流水线里"业务意图"与"链上字节"之间的中间表示：业务侧只描述
 * {@code (to, data, value)} 三元组；编排器（{@link GaslessRelayer}）再把它升级成单笔
 * SafeTx 或 MultiSend 批次。</p>
 *
 * <p>Polymarket relayer 的所有标准 calldata 工厂（{@link Calldata#approveErc20}、
 * {@link Calldata#setApprovalForAll}、{@link Calldata#transferErc20}、
 * {@link Calldata#redeemPositions}）都默认 {@code value=0}；只有极少场景会出现非零
 * native value，到时再用全参构造。</p>
 *
 * @param to    被调合约地址（USDC.e / CTF / Adapter / Exchange / ...）
 * @param data  ABI 编码后的 calldata（含 4 字节 selector）
 * @param value native 转账金额，默认 0
 */
public record RelayerTx(Address to, byte[] data, BigInteger value) {

    public RelayerTx {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(data, "data");
        if (value == null) {
            value = BigInteger.ZERO;
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException("value 不能为负：" + value);
        }
    }

    /** 等价于 {@code new RelayerTx(to, data, BigInteger.ZERO)}，业务代码更短。 */
    public static RelayerTx call(Address to, byte[] data) {
        return new RelayerTx(to, data, BigInteger.ZERO);
    }
}
