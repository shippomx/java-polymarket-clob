package com.polymarket.clob.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AddressTest {
    @Test
    void parsesHexWithPrefix() {
        Address a = Address.fromHex("0x4bFb41d5B3570DeFd03C39a9A4D8dE6Bd8B8982E");
        assertThat(a.toHex()).isEqualToIgnoringCase("0x4bFb41d5B3570DeFd03C39a9A4D8dE6Bd8B8982E");
    }

    @Test
    void parsesHexWithoutPrefix() {
        Address a = Address.fromHex("4bFb41d5B3570DeFd03C39a9A4D8dE6Bd8B8982E");
        assertThat(a.toBytes()).hasSize(20);
    }

    @Test
    void rejectsTooShort() {
        assertThatThrownBy(() -> Address.fromHex("0xabc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toHexReturnsEip55Checksum() {
        Address a = Address.fromHex("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed");
        assertThat(a.toHex()).isEqualTo("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed");
        assertThat(a.toString()).isEqualTo("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed");
        assertThat(a.toString()).hasSize(42);
    }

    @Test
    void equalsIsCaseInsensitive() {
        Address a = Address.fromHex("0xABCDEF1234567890ABCDEF1234567890ABCDEF12");
        Address b = Address.fromHex("0xabcdef1234567890abcdef1234567890abcdef12");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void fromBytesRoundTrip() {
        Address a = Address.fromBytes(new byte[20]);
        assertThat(a.toBytes()).hasSize(20);
    }

    @Test
    void fromBytesRejectsWrongLength() {
        assertThatThrownBy(() -> Address.fromBytes(new byte[19]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromHexRejectsNull() {
        assertThatThrownBy(() -> Address.fromHex(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void fromBytesRejectsNull() {
        assertThatThrownBy(() -> Address.fromBytes(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void equalsHandlesNullAndOtherTypes() {
        Address a = Address.fromHex("0xABCDEF1234567890ABCDEF1234567890ABCDEF12");
        assertThat(a.equals(null)).isFalse();
        assertThat(a.equals("not-an-address")).isFalse();
    }

    @Test
    void zeroAddressIsAllZeros() {
        assertThat(Address.ZERO.toBytes()).hasSize(20).containsOnly((byte) 0);
        assertThat(Address.ZERO.toString()).startsWith("0x0000000000");
    }

    @Test
    void toBytesIsDefensiveClone() {
        Address a = Address.fromHex("0xABCDEF1234567890ABCDEF1234567890ABCDEF12");
        byte[] b = a.toBytes();
        b[0] = 0x00;
        byte[] c = a.toBytes();
        assertThat(c[0]).isEqualTo((byte) 0xAB);
    }
}
