package com.polymarket.clob.auth;

import com.polymarket.clob.model.Address;

import java.math.BigInteger;
import java.util.Objects;

/**
 * L1 认证的 EIP-712 primary type，与 Rust {@code rs-clob-client/src/auth.rs} 中的
 * {@code sol! struct ClobAuth} 对齐：
 *
 * <pre>
 * struct ClobAuth {
 *     address address;
 *     string  timestamp;
 *     uint256 nonce;
 *     string  message;
 * }
 * </pre>
 *
 * <p>固定 message 文案为上游协议常量 {@link #CANONICAL_MESSAGE}，客户端不应改写。</p>
 */
public final class ClobAuth {

    public static final String CANONICAL_MESSAGE =
            "This message attests that I control the given wallet";

    public static final String DOMAIN_NAME = "ClobAuthDomain";
    public static final String DOMAIN_VERSION = "1";

    private final Address address;
    private final String timestamp;
    private final BigInteger nonce;
    private final String message;

    private ClobAuth(Address address, String timestamp, BigInteger nonce, String message) {
        this.address = address;
        this.timestamp = timestamp;
        this.nonce = nonce;
        this.message = message;
    }

    /** 标准构造：使用协议固定的 message 文案。 */
    public static ClobAuth of(Address address, long timestamp, BigInteger nonce) {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(nonce, "nonce");
        return new ClobAuth(address, Long.toString(timestamp), nonce, CANONICAL_MESSAGE);
    }

    public Address address() { return address; }
    public String timestamp() { return timestamp; }
    public BigInteger nonce() { return nonce; }
    public String message() { return message; }
}
