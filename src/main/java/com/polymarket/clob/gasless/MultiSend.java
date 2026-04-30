package com.polymarket.clob.gasless;

import com.polymarket.clob.model.Address;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Function;
import org.web3j.utils.Numeric;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * Gnosis MultiSend（{@code MultiSendCallOnly}）payload 与 ABI 包装工具。
 *
 * <p>Polymarket relayer 用 Polygon 上的 {@code 0xA238CBeb142c10Ef7Ad8442C6D1f9E89e07e7761}
 * 作为统一 multisend；Amoy testnet 也是同一地址。SafeTx 调用对象设为该地址、{@code operation=1}
 * （DelegateCall），让 Safe 在自身 storage 上下文里依次调用每笔子事务，从而把多笔授权合并为
 * 一次 nonce、一次 relayer 配额。</p>
 *
 * <h3>布局（与 EIP-2020 / @gnosis.pm/safe-contracts 完全一致）</h3>
 * <pre>
 * 内部 payload = ‖( uint8 operation || address to (20) || uint256 value (32) ||
 *                   uint256 dataLen (32) || bytes data )+
 * 外层 calldata = 0x8d80ff0a || abi.encode(bytes payload)
 * </pre>
 * 其中外层 selector {@code 0x8d80ff0a} 即 {@code multiSend(bytes)}。
 */
public final class MultiSend {

    /**
     * Polygon 主网官方 MultiSendCallOnly；Amoy 也使用同一地址（与 Rust client 一致）。
     */
    public static final Address MULTISEND_CALL_ONLY =
            Address.fromHex("0xA238CBeb142c10Ef7Ad8442C6D1f9E89e07e7761");

    private MultiSend() {}

    /**
     * 把 N 笔 {@link RelayerTx} 顺次拼成 multisend 内部 payload；不含外层 ABI 包装。
     *
     * <p>每笔子事务的 {@code operation} 固定写 {@code 0=Call}——Polymarket 流程下所有授权和
     * redeem/transfer 都是普通 Call；如未来出现需要内嵌 DelegateCall 的子事务，请走单独路径。</p>
     */
    public static byte[] encodePayload(List<RelayerTx> txs) {
        Objects.requireNonNull(txs, "txs");
        if (txs.isEmpty()) {
            throw new IllegalArgumentException("txs 不能为空");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            for (RelayerTx t : txs) {
                out.write(0); // operation = Call
                out.write(t.to().toBytes());
                out.write(Words.leftPad32(t.value() == null ? BigInteger.ZERO : t.value()));
                out.write(Words.leftPad32(BigInteger.valueOf(t.data().length)));
                out.write(t.data());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }

    /**
     * 把 multisend payload 包成 {@code multiSend(bytes)} 的 calldata：{@code 0x8d80ff0a + abi.encode(bytes)}。
     */
    public static byte[] encodeCall(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        Function fn = new Function(
                "multiSend",
                List.of(new DynamicBytes(payload)),
                List.of());
        return Numeric.hexStringToByteArray(FunctionEncoder.encode(fn));
    }

    /** 一步到位：{@code encodeCall(encodePayload(txs))}。 */
    public static byte[] encode(List<RelayerTx> txs) {
        return encodeCall(encodePayload(txs));
    }
}
