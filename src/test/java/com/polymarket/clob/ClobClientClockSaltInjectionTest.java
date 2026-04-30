package com.polymarket.clob;

import com.polymarket.clob.order.SaltSource;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 {@link ClobClient} 通过 builder 注入 {@link Clock} 和 {@link SaltSource}，
 * 用于 parity 测试中固定时间戳与 salt，产出可重现的 golden 向量。
 */
class ClobClientClockSaltInjectionTest {

    @Test
    void builderAcceptsFixedClock() {
        Instant frozen = Instant.ofEpochSecond(1700000000);
        Clock fixed = Clock.fixed(frozen, ZoneOffset.UTC);

        ClobClient client = ClobClient.builder()
                .endpoint(URI.create("https://example.invalid"))
                .chainId(137)
                .clock(fixed)
                .build();

        assertThat(client.clock().instant()).isEqualTo(frozen);
    }

    @Test
    void builderAcceptsFixedSaltSource() {
        SaltSource fixed = SaltSource.fixed(BigInteger.valueOf(42));

        ClobClient client = ClobClient.builder()
                .endpoint(URI.create("https://example.invalid"))
                .chainId(137)
                .saltSource(fixed)
                .build();

        assertThat(client.saltSource().next()).isEqualTo(BigInteger.valueOf(42));
    }

    @Test
    void defaultsAreSystemUtcAndSecureRandom() {
        ClobClient client = ClobClient.builder()
                .endpoint(URI.create("https://example.invalid"))
                .chainId(137)
                .build();
        assertThat(client.clock()).isNotNull();
        assertThat(client.saltSource()).isNotNull();
    }
}
