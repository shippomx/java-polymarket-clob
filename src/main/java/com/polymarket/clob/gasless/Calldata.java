package com.polymarket.clob.gasless;

import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.ContractConfig;
import com.polymarket.clob.model.ContractRegistry;
import com.polymarket.clob.model.Hash32;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Polymarket gasless 流水线常用 calldata 工厂。
 *
 * <p>本类只产生<b>纯字节</b>（{@code byte[]}）；如何把 calldata 包装成 SafeTx 子事务（指定 target、
 * 0 value、Call/DelegateCall）由 {@link RelayerTx} 与 {@link MultiSend} 负责。为方便业务侧
 * 一步到位，每个 calldata 工厂都同时给出对应的 {@code *Tx(...)} 包装方法（{@code (token, ...)}
 * → {@link RelayerTx}）。</p>
 *
 * <h3>提供的 selector 集合</h3>
 * <ul>
 *   <li>{@code approve(address,uint256)} —— ERC20 授权（默认 {@code MAX_UINT256}）；</li>
 *   <li>{@code setApprovalForAll(address,bool)} —— ERC1155 授权（默认 {@code true}）；</li>
 *   <li>{@code transfer(address,uint256)} —— ERC20 直转，给 Withdraw Direct 用；</li>
 *   <li>{@code redeemPositions(address,bytes32,bytes32,uint256[])} —— CTF 兑付，给 Redeem 用。</li>
 * </ul>
 *
 * <p>业务编排 {@link #standardApprovalTxs(long)} 一次性吐出 6 笔标准授权（USDC.e × 3 spender +
 * CTF × 3 spender），与 Polymarket 上线流程的 EnableTrade SafeTx 内容字节级一致。</p>
 *
 * <p>线程安全（无可变状态）。</p>
 */
public final class Calldata {

    /** ERC-20 / ERC-1155 max approval 的常量（{@code 2^256 - 1}）。 */
    public static final BigInteger MAX_UINT256 = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);

    private Calldata() {}

    // =========================================================
    //  ERC20 approve(spender, amount)
    // =========================================================

    /** {@code approve(spender, MAX_UINT256)} 的 calldata；Polymarket 标准授权用此重载。 */
    public static byte[] approveErc20(Address spender) {
        return approveErc20(spender, MAX_UINT256);
    }

    /** {@code approve(spender, amount)} 的 calldata，金额可指定。 */
    public static byte[] approveErc20(Address spender, BigInteger amount) {
        Objects.requireNonNull(spender, "spender");
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount 不能为负：" + amount);
        }
        Function fn = new Function(
                "approve",
                List.of(
                        new org.web3j.abi.datatypes.Address(160, spender.toLowerHex()),
                        new Uint256(amount)),
                List.of());
        return Numeric.hexStringToByteArray(FunctionEncoder.encode(fn));
    }

    /** 包装版：{@code (token).approve(spender, MAX_UINT256)} 的 SafeTx 子事务。 */
    public static RelayerTx approveErc20Tx(Address token, Address spender) {
        return RelayerTx.call(token, approveErc20(spender));
    }

    /** 包装版：{@code (token).approve(spender, amount)} 的 SafeTx 子事务。 */
    public static RelayerTx approveErc20Tx(Address token, Address spender, BigInteger amount) {
        return RelayerTx.call(token, approveErc20(spender, amount));
    }

    // =========================================================
    //  ERC1155 setApprovalForAll(operator, approved)
    // =========================================================

    /** {@code setApprovalForAll(operator, true)} 的 calldata；标准授权批次用此重载。 */
    public static byte[] setApprovalForAll(Address operator) {
        return setApprovalForAll(operator, true);
    }

    public static byte[] setApprovalForAll(Address operator, boolean approved) {
        Objects.requireNonNull(operator, "operator");
        Function fn = new Function(
                "setApprovalForAll",
                List.of(
                        new org.web3j.abi.datatypes.Address(160, operator.toLowerHex()),
                        new Bool(approved)),
                List.of());
        return Numeric.hexStringToByteArray(FunctionEncoder.encode(fn));
    }

    /** 包装版：{@code (erc1155).setApprovalForAll(operator, true)}。 */
    public static RelayerTx setApprovalForAllTx(Address erc1155, Address operator) {
        return RelayerTx.call(erc1155, setApprovalForAll(operator, true));
    }

    /** 包装版：{@code (erc1155).setApprovalForAll(operator, approved)}。 */
    public static RelayerTx setApprovalForAllTx(Address erc1155, Address operator, boolean approved) {
        return RelayerTx.call(erc1155, setApprovalForAll(operator, approved));
    }

    // =========================================================
    //  ERC20 transfer(to, amount) —— Withdraw Direct
    // =========================================================

    /**
     * {@code transfer(to, amount)} 的 calldata。
     *
     * <p>Polymarket "提现到外部地址（Direct）" 的核心 calldata：包成 SafeTx 后由 Safe 把 USDC.e
     * 直接转给目标地址，相对 Bridge 路径无需上游桥服务。{@code amount} 是 USDC.e 6 位精度的最小单位
     * （例如 1 USDC = {@code 1_000_000}）。</p>
     */
    public static byte[] transferErc20(Address to, BigInteger amount) {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount 必须为正：" + amount);
        }
        Function fn = new Function(
                "transfer",
                List.of(
                        new org.web3j.abi.datatypes.Address(160, to.toLowerHex()),
                        new Uint256(amount)),
                List.of());
        return Numeric.hexStringToByteArray(FunctionEncoder.encode(fn));
    }

    /** 包装版：{@code (token).transfer(to, amount)} 的 SafeTx 子事务。 */
    public static RelayerTx transferErc20Tx(Address token, Address to, BigInteger amount) {
        return RelayerTx.call(token, transferErc20(to, amount));
    }

    // =========================================================
    //  ConditionalTokens redeemPositions(...) —— Redeem
    // =========================================================

    /**
     * {@code redeemPositions(IERC20 collateralToken, bytes32 parentCollectionId, bytes32 conditionId,
     * uint256[] indexSets)} 的 calldata。
     *
     * <p>Polymarket "已结算市场的兑付" 流程：把市场结算后用户名下的 outcome token 销毁，按胜负把
     * collateral（USDC.e）退回 Safe。{@code parentCollectionId} 在普通市场为 {@code bytes32(0)}；
     * {@code indexSets} 是要兑付的 outcome 位图集合（如二元市场 {@code [1, 2]} = YES + NO）。</p>
     *
     * <p>调用对象（{@code to}）应是 {@link ContractConfig#conditionalTokens()}（即 CTF 合约），
     * 而非 NegRiskAdapter；neg-risk 市场在 NegRiskAdapter 上有不同 selector，由 BE 按市场类型分流。</p>
     */
    public static byte[] redeemPositions(Address collateralToken,
                                         Hash32 parentCollectionId,
                                         Hash32 conditionId,
                                         List<BigInteger> indexSets) {
        Objects.requireNonNull(collateralToken, "collateralToken");
        Objects.requireNonNull(parentCollectionId, "parentCollectionId");
        Objects.requireNonNull(conditionId, "conditionId");
        Objects.requireNonNull(indexSets, "indexSets");
        if (indexSets.isEmpty()) {
            throw new IllegalArgumentException("indexSets 不能为空");
        }
        List<Uint256> uints = new ArrayList<>(indexSets.size());
        for (BigInteger idx : indexSets) {
            if (idx == null || idx.signum() <= 0) {
                throw new IllegalArgumentException("indexSet 必须为正：" + idx);
            }
            uints.add(new Uint256(idx));
        }
        Function fn = new Function(
                "redeemPositions",
                List.of(
                        new org.web3j.abi.datatypes.Address(160, collateralToken.toLowerHex()),
                        new Bytes32(parentCollectionId.toBytes()),
                        new Bytes32(conditionId.toBytes()),
                        new DynamicArray<>(Uint256.class, uints)),
                List.of());
        return Numeric.hexStringToByteArray(FunctionEncoder.encode(fn));
    }

    /**
     * 包装版：{@code (ctf).redeemPositions(collateralToken, parentCollectionId, conditionId, indexSets)}。
     *
     * @param ctf {@link ContractConfig#conditionalTokens()}
     */
    public static RelayerTx redeemPositionsTx(Address ctf,
                                              Address collateralToken,
                                              Hash32 parentCollectionId,
                                              Hash32 conditionId,
                                              List<BigInteger> indexSets) {
        return RelayerTx.call(ctf,
                redeemPositions(collateralToken, parentCollectionId, conditionId, indexSets));
    }

    // =========================================================
    //  6 笔标准授权批次（业务编排）
    // =========================================================

    /**
     * Polymarket 上线流程的标准授权批次：USDC.e × 3 spender + CTF × 3 spender。
     *
     * <table>
     *   <tr><th>spender</th><th>USDC.e (approve)</th><th>CTF (setApprovalForAll)</th></tr>
     *   <tr><td>CTFExchange</td><td>✓</td><td>✓</td></tr>
     *   <tr><td>NegRiskCTFExchange</td><td>✓</td><td>✓</td></tr>
     *   <tr><td>NegRiskAdapter</td><td>✓</td><td>✓</td></tr>
     * </table>
     *
     * <p>已包含 NegRiskAdapter 作为 spender，覆盖 Redeem (A) 流程的所有授权前置——所以 Redeem 流程
     * 只需要再产生一笔 {@link #redeemPositions} calldata，不需要追加任何 approve。</p>
     */
    public static List<RelayerTx> standardApprovalTxs(long chainId) {
        ContractConfig std = ContractRegistry.contractConfig(chainId, false)
                .orElseThrow(() -> new IllegalStateException("standard 合约缺失 chainId=" + chainId));
        ContractConfig neg = ContractRegistry.contractConfig(chainId, true)
                .orElseThrow(() -> new IllegalStateException("neg-risk 合约缺失 chainId=" + chainId));
        Address usdc = std.collateral();
        Address ctf = std.conditionalTokens();
        Address ctfExchange = std.exchange();
        Address negRiskExchange = neg.exchange();
        Address negRiskAdapter = neg.negRiskAdapter()
                .orElseThrow(() -> new IllegalStateException("neg-risk adapter 缺失 chainId=" + chainId));

        List<RelayerTx> txs = new ArrayList<>(6);
        txs.add(approveErc20Tx(usdc, ctfExchange));
        txs.add(approveErc20Tx(usdc, negRiskExchange));
        txs.add(approveErc20Tx(usdc, negRiskAdapter));
        txs.add(setApprovalForAllTx(ctf, ctfExchange));
        txs.add(setApprovalForAllTx(ctf, negRiskExchange));
        txs.add(setApprovalForAllTx(ctf, negRiskAdapter));
        return txs;
    }

    /**
     * V2 上线（2026-04-28）后的标准授权批次：在 V1 6 笔之上追加 V2 spender 授权。
     *
     * <p>新增 4 笔（V2 不再共享 V1 spender）：
     * <ul>
     *   <li>USDC.e → V2 CTF Exchange (approve)</li>
     *   <li>USDC.e → V2 NegRisk Exchange (approve)</li>
     *   <li>CTF → V2 CTF Exchange (setApprovalForAll)</li>
     *   <li>CTF → V2 NegRisk Exchange (setApprovalForAll)</li>
     * </ul>
     * </p>
     *
     * <p><b>关于 pUSD</b>：py-clob-client-v2 与 rs-clob-client-v2 都仅对 USDC.e 走 approve；
     * pUSD 的 mint/burn 由 V2 Exchange 内部处理，无需 EOA/Safe 主动 approve pUSD 给 spender。
     * 与上游 SDK 行为对齐，本批次<b>不对 pUSD 做 approve</b>。如需自定义流程（如直接转 pUSD），
     * 可单独调用 {@link #approveErc20Tx(Address, Address)} + {@link com.polymarket.clob.model.ContractConfig#pUsd()}。</p>
     *
     * <p>触发条件：V2 上线后任何 EOA/Safe 在 V2 Exchange 上首次下单前都必须先跑这 10 笔批次；
     * 已经跑过 V1 6 笔的存量用户只需追加 V2 增量 4 笔（见 {@link #v2OnlyApprovalTxs(long)}）。</p>
     */
    public static List<RelayerTx> standardApprovalTxsV2(long chainId) {
        List<RelayerTx> all = new ArrayList<>(standardApprovalTxs(chainId));
        all.addAll(v2OnlyApprovalTxs(chainId));
        return all;
    }

    /**
     * V2 增量授权批次（4 笔）。给已经跑完 V1 标准授权的存量用户使用，避免重复 approve V1 spender。
     *
     * @throws IllegalStateException 当链上未部署 V2 exchange / neg-risk exchange 时
     */
    public static List<RelayerTx> v2OnlyApprovalTxs(long chainId) {
        ContractConfig std = ContractRegistry.contractConfig(chainId, false)
                .orElseThrow(() -> new IllegalStateException("standard 合约缺失 chainId=" + chainId));
        Address usdc = std.collateral();
        Address ctf = std.conditionalTokens();

        Address exchangeV2 = ContractRegistry.exchangeV2(chainId, false)
                .orElseThrow(() -> new IllegalStateException("V2 exchange 未部署 chainId=" + chainId));
        Address negRiskExchangeV2 = ContractRegistry.exchangeV2(chainId, true)
                .orElseThrow(() -> new IllegalStateException("V2 neg-risk exchange 未部署 chainId=" + chainId));

        List<RelayerTx> txs = new ArrayList<>(4);
        txs.add(approveErc20Tx(usdc, exchangeV2));
        txs.add(approveErc20Tx(usdc, negRiskExchangeV2));
        txs.add(setApprovalForAllTx(ctf, exchangeV2));
        txs.add(setApprovalForAllTx(ctf, negRiskExchangeV2));
        return txs;
    }
}
