package com.polymarket.clob.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Hash32Test {
    @Test
    void parsesHex() {
        Hash32 h = Hash32.fromHex("0xd21df8dc65880a8606f09fe0ce3df9b8869287ab0b058be05aa9e8af6330a00b");
        assertThat(h.toBytes()).hasSize(32);
    }

    @Test
    void rejectsWrongLength() {
        assertThatThrownBy(() -> Hash32.fromHex("0xabcd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromBytesRoundTrip() {
        Hash32 h = Hash32.fromBytes(new byte[32]);
        assertThat(h.toBytes()).hasSize(32);
    }

    @Test
    void toBytesIsDefensiveClone() {
        Hash32 h = Hash32.fromBytes(new byte[]{
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});
        byte[] b = h.toBytes();
        b[0] = 0;
        assertThat(h.toBytes()[0]).isEqualTo((byte) 1);
    }

    @Test
    void fromBytesRejectsWrongLength() {
        assertThatThrownBy(() -> Hash32.fromBytes(new byte[31]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromHexRejectsNull() {
        assertThatThrownBy(() -> Hash32.fromHex(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void equalsAndHashCodeAreConsistent() {
        String hex = "0xd21df8dc65880a8606f09fe0ce3df9b8869287ab0b058be05aa9e8af6330a00b";
        Hash32 h1 = Hash32.fromHex(hex);
        Hash32 h2 = Hash32.fromHex(hex);
        assertThat(h1).isEqualTo(h2);
        assertThat(h1.hashCode()).isEqualTo(h2.hashCode());
        assertThat(h1.equals(null)).isFalse();
        assertThat(h1.equals("x")).isFalse();
    }

    @Test
    void toHexIsLowercase() {
        String upper = "0x" + "AA".repeat(32);
        Hash32 h = Hash32.fromHex(upper);
        assertThat(h.toHex()).isEqualTo("0x" + "aa".repeat(32));
    }
}
