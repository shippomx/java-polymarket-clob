package com.polymarket.clob.gamma;

import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SiweMessageTest {

    @Test
    void buildsExpectedSiweTextForPolygon() {
        Instant issued = Instant.parse("2026-05-06T14:56:25Z");
        Instant expires = issued.plusSeconds(7L * 24 * 3600);
        String text = SiweMessage.build(
                Address.fromHex("0x4cAfCf2D9A032f57088872f6546Bb68305d209D7"),
                137,
                "abc123nonce",
                issued,
                expires);

        String expected = """
                polymarket.com wants you to sign in with your Ethereum account:
                0x4cAfCf2D9A032f57088872f6546Bb68305d209D7

                Welcome to Polymarket! Sign to connect.

                URI: https://polymarket.com
                Version: 1
                Chain ID: 137
                Nonce: abc123nonce
                Issued At: 2026-05-06T14:56:25Z
                Expiration Time: 2026-05-13T14:56:25Z""";
        assertThat(text).isEqualTo(expected);
    }
}
