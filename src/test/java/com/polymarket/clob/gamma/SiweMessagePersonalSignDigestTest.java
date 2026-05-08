package com.polymarket.clob.gamma;

import org.junit.jupiter.api.Test;
import org.web3j.crypto.Sign;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SiweMessagePersonalSignDigestTest {
    @Test
    void digestMatchesWeb3jEip191() {
        String msg = "hello SIWE";
        byte[] expected = Sign.getEthereumMessageHash(msg.getBytes(StandardCharsets.UTF_8));
        assertThat(SiweMessage.personalSignDigest(msg)).containsExactly(expected);
    }
}
