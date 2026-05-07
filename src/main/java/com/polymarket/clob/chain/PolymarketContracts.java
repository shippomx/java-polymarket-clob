package com.polymarket.clob.chain;

import com.polymarket.clob.model.Address;
import org.web3j.crypto.Hash;

import java.nio.charset.StandardCharsets;

/**
 * Polymarket Deposit Wallet 流相关的链上地址 + EIP-712 类型字符串集中存放。
 *
 * <p>Polygon mainnet (chainId 137) 唯一目标。地址来源：
 * docs/EOA_TO_ORDER.md §8（HAR 反向工程） 与
 * clob-client-v2/src/order-utils/abi/*.ts。</p>
 *
 * <p>所有 EIP-712 type strings 必须与 TS 源字节级一致；改动会导致链上 1271
 * 验签失败。</p>
 */
public final class PolymarketContracts {

    private PolymarketContracts() {}

    // SOURCE: docs/EOA_TO_ORDER.md §8
    public static final Address FACTORY            = Address.fromHex("0x00000000000Fb5C9ADea0298D729A0CB3823Cc07");
    public static final Address IMPLEMENTATION     = Address.fromHex("0x58CA52ebe0DadfdF531Cde7062e76746de4Db1eB");

    // SOURCE: clob-client-v2/src/order-utils/abi/*.ts
    public static final Address USDC_E             = Address.fromHex("0xC011a7E12a19f7B1f670d46F03B03f3342E82DFB");
    public static final Address USDC_NATIVE        = Address.fromHex("0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174");
    public static final Address CTF                = Address.fromHex("0x4D97DCd97eC945f40cF65F87097ACe5EA0476045");
    public static final Address EXCHANGE_V2        = Address.fromHex("0xE111180000d2663C0091e4f400237545B87B996B");
    public static final Address NEG_RISK_EXCHANGE_V2 = Address.fromHex("0xe2222d279d744050d28e00520010520000310F59");
    public static final Address NEG_RISK_ADAPTER   = Address.fromHex("0xd91E80cF2E7be2e162c6513ceD06f1dD0dA35296");
    public static final Address PUSD_QUOTER        = Address.fromHex("0x93070a847efef7f70739046a929d47a521f5b8ee");
    public static final Address NEW_SPENDER_A      = Address.fromHex("0xada100db00ca00073811820692005400218fce1f");
    public static final Address NEW_SPENDER_B      = Address.fromHex("0xada2005600dec949baf300f4c6120000bdb6eaab");
    public static final Address PARLAY             = Address.fromHex("0xf3cfb6a6ebfeb51876289eb235719eb1c65252b0");

    // ----- EIP-712 域 -----
    // SOURCE: clob-client-v2/src/order-utils/exchangeOrderBuilderV2.ts (常量名 CTF_EXCHANGE_V2_DOMAIN_NAME)
    public static final String CTF_EXCHANGE_V2_DOMAIN_NAME    = "Polymarket CTF Exchange";
    public static final String CTF_EXCHANGE_V2_DOMAIN_VERSION = "2";

    public static final String DEPOSIT_WALLET_DOMAIN_NAME    = "DepositWallet";
    public static final String DEPOSIT_WALLET_DOMAIN_VERSION = "1";

    public static final String CLOB_AUTH_DOMAIN_NAME    = "ClobAuthDomain";
    public static final String CLOB_AUTH_DOMAIN_VERSION = "1";

    // ----- EIP-712 type strings -----
    // SOURCE: clob-client-v2/src/order-utils/abi/orderAbi.ts (CTF_EXCHANGE_V2_ORDER_STRUCT 序列化)
    public static final String ORDER_TYPE_STRING =
            "Order(uint256 salt,address maker,address signer,uint256 tokenId,"
            + "uint256 makerAmount,uint256 takerAmount,uint8 side,uint8 signatureType,"
            + "uint256 timestamp,bytes32 metadata,bytes32 builder)";

    // ERC-7739 嵌套：内层 TypedDataSign 类型字符串。
    // SOURCE: clob-client-v2/src/order-utils/exchangeOrderBuilderV2.ts (TYPED_DATA_SIGN_STRUCT)
    public static final String TYPED_DATA_SIGN_TYPE_STRING =
            "TypedDataSign(Order contents,string name,string version,uint256 chainId,"
            + "address verifyingContract,bytes32 salt)" + ORDER_TYPE_STRING;

    /**
     * Precomputed keccak256 hashes of the type strings above.
     *
     * <p><b>Mutable array warning:</b> Java {@code byte[]} static finals are
     * shallowly immutable — the reference is final but the bytes are not. Callers
     * must <b>treat these as read-only</b>. Mutating these arrays corrupts the
     * constant for all callers in the JVM. Internal SDK callers comply.</p>
     */
    public static final byte[] ORDER_TYPE_HASH =
            Hash.sha3(ORDER_TYPE_STRING.getBytes(StandardCharsets.US_ASCII));

    public static final byte[] TYPED_DATA_SIGN_TYPE_HASH =
            Hash.sha3(TYPED_DATA_SIGN_TYPE_STRING.getBytes(StandardCharsets.US_ASCII));

    /** ERC-7739 末尾 uint16 BE = ORDER_TYPE_STRING 字节长度。 */
    public static int orderTypeStringByteLength() {
        return ORDER_TYPE_STRING.length();   // US-ASCII: char count == byte count
    }
}
