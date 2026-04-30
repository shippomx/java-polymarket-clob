package com.polymarket.clob.parity;

import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.auth.LocalSigner;
import com.polymarket.clob.auth.SignatureType;
import com.polymarket.clob.auth.Signer;
import com.polymarket.clob.model.Address;

/**
 * <h2>Hardhat / Anvil account #0 — 永远公开的测试私钥</h2>
 *
 * <p>仅用于 parity fixture / oracle 生成与 offline 测试。该私钥已在 Ethereum 全网公开
 * 多年，<b>对应地址不持有任何资产，任何使用此 key 调链的真实交易必将失败</b>。Track A
 * 默认禁用网络（spec §9.2）；Track B 永远只读。</p>
 *
 * <p>对应地址：{@code 0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266}（Hardhat #0）。</p>
 *
 * <p>API credentials 是固定的合法 base64url placeholder，仅用于 L2 HMAC 计算的
 * 可重现性——签名期望落在 fixture 里，不会被任何真实网关接受。</p>
 */
public final class FixedTestKeys {

    /** Hardhat / Anvil account #0 私钥（公开常量，零风险）。 */
    public static final String PRIVATE_KEY_HEX =
            "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";

    /** Hardhat / Anvil account #0 地址（{@link #PRIVATE_KEY_HEX} 派生）。 */
    public static final Address ADDRESS =
            Address.fromHex("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266");

    /**
     * 占位 API key UUID（v4 全 1，固定常量）。
     *
     * <p>不是真实 CLOB API key——仅用于 L2 HMAC 输入侧的可重现性，
     * 因此 Java 与 Rust oracle 必须用同一份。</p>
     */
    public static final String API_KEY = "11111111-1111-1111-1111-111111111111";

    /**
     * 占位 secret，固定 base64url 字符串。<b>解码后的 32 字节即 HMAC key</b>，
     * Java 与 Rust 两端必须使用相同的 secret 字面量，签名才会一致。
     *
     * <p>"test-parity-secret-32-bytes-fix0" 长度 32（4 的倍数），全部为合法
     * base64url 字符（含 {@code -}），可以走标准 base64url 解码而不报错。</p>
     */
    public static final String API_SECRET = "test-parity-secret-32-bytes-fix0";

    /** 占位 passphrase。同 secret 注释，仅用于 L2 HMAC sign 的输入侧。 */
    public static final String API_PASSPHRASE = "test-parity-passphrase";

    /** 默认签名类型：EOA（funder == signer.address()）。 */
    public static final SignatureType DEFAULT_SIGNATURE_TYPE = SignatureType.EOA;

    /** 占位 API credentials（仅用于 L2 HMAC 计算）。 */
    public static final ApiCredentials API_CREDENTIALS =
            new ApiCredentials(API_KEY, API_SECRET, API_PASSPHRASE);

    private FixedTestKeys() {}

    /** 用 {@link #PRIVATE_KEY_HEX} 创建 {@link LocalSigner}，每次调用返回新实例。 */
    public static Signer signer() {
        return LocalSigner.fromPrivateKey(PRIVATE_KEY_HEX);
    }
}
