package com.polymarket.clob.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 不可变 32 字节哈希。等价于 Rust `alloy::primitives::B256`。
 *
 * <p>比较语义：逐字节比较。序列化：{@link #toHex()} 输出 0x 前缀 64 字符全小写字符串。</p>
 */
public final class Hash32 {

    private static final HexFormat HEX = HexFormat.of();

    private final byte[] bytes;

    private Hash32(byte[] bytes) {
        this.bytes = bytes;
    }

    @JsonCreator
    public static Hash32 fromHex(String hex) {
        Objects.requireNonNull(hex, "hex");
        String body = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        if (body.length() != 64) {
            throw new IllegalArgumentException("Hash32 hex must be 64 chars (32 bytes): got " + body.length());
        }
        return new Hash32(HEX.parseHex(body.toLowerCase()));
    }

    public static Hash32 fromBytes(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length != 32) {
            throw new IllegalArgumentException("Hash32 bytes must be length 32, got " + bytes.length);
        }
        return new Hash32(bytes.clone());
    }

    /**
     * 返回底层 32 字节的防御性拷贝。与 {@link #fromBytes(byte[])} 命名对齐。
     */
    public byte[] toBytes() {
        return bytes.clone();
    }

    @JsonValue
    public String toHex() {
        return "0x" + HEX.formatHex(bytes);
    }

    @Override public String toString() { return toHex(); }

    @Override public boolean equals(Object o) {
        return this == o || (o instanceof Hash32 h && Arrays.equals(bytes, h.bytes));
    }

    @Override public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
