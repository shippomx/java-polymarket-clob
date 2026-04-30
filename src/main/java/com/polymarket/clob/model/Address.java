package com.polymarket.clob.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

import org.web3j.crypto.Keys;

/**
 * 不可变 20 字节 EVM 地址。等价于 Rust `alloy::primitives::Address`。
 *
 * <p>比较语义：逐字节比较（{@code fromHex} 前已归一化为小写，因此大小写不影响 equals）。
 * 序列化：{@link #toHex()} 输出 0x 前缀 40 字符 EIP-55 checksum 字符串。</p>
 */
public final class Address {

    private static final HexFormat HEX = HexFormat.of();

    public static final Address ZERO = new Address(new byte[20]);

    private final byte[] bytes;

    private Address(byte[] bytes) {
        this.bytes = bytes;
    }

    @JsonCreator
    public static Address fromHex(String hex) {
        Objects.requireNonNull(hex, "hex");
        String body = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        if (body.length() != 40) {
            throw new IllegalArgumentException("Address hex must be 40 chars (20 bytes): got " + body.length());
        }
        return new Address(HEX.parseHex(body.toLowerCase()));
    }

    public static Address fromBytes(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length != 20) {
            throw new IllegalArgumentException("Address bytes must be length 20, got " + bytes.length);
        }
        return new Address(bytes.clone());
    }

    /**
     * 返回底层 20 字节的防御性拷贝。与 {@link #fromBytes(byte[])} 命名对齐。
     */
    public byte[] toBytes() {
        return bytes.clone();
    }

    @JsonValue
    public String toHex() {
        return Keys.toChecksumAddress("0x" + HEX.formatHex(bytes));
    }

    /**
     * 全小写的 0x 前缀 hex。与 Rust {@code encode_hex_with_prefix} 对齐，
     * 用于协议约定 lowercase 的场景（{@code POLY_ADDRESS} 请求头、旧版 API 的 query 参数）。
     */
    public String toLowerHex() {
        return "0x" + HEX.formatHex(bytes);
    }

    @Override public String toString() { return toHex(); }

    @Override public boolean equals(Object o) {
        return this == o || (o instanceof Address a && Arrays.equals(bytes, a.bytes));
    }

    @Override public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
