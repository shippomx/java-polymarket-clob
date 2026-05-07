package com.polymarket.clob.onboard;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnboardingConfigTest {

    @Test
    void builderRequiresRpcUrl() {
        assertThatThrownBy(() -> OnboardingConfig.builder().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rpcUrl");
    }

    @Test
    void builderAppliesDefaults() {
        OnboardingConfig cfg = OnboardingConfig.builder()
                .rpcUrl(URI.create("https://polygon-rpc.com"))
                .build();

        assertThat(cfg.chainId()).isEqualTo(137);
        assertThat(cfg.gammaHost()).isEqualTo(URI.create("https://gamma-api.polymarket.com"));
        assertThat(cfg.relayerHost()).isEqualTo(URI.create("https://relayer-v2.polymarket.com"));
        assertThat(cfg.clobHost()).isEqualTo(URI.create("https://clob.polymarket.com"));
        assertThat(cfg.relayerPollInterval()).isEqualTo(Duration.ofSeconds(2));
        assertThat(cfg.relayerMaxAttempts()).isEqualTo(90);
        assertThat(cfg.testOrder()).isEmpty();
    }

    @Test
    void unsupportedChainRejected() {
        assertThatThrownBy(() -> OnboardingConfig.builder()
                .rpcUrl(URI.create("https://example"))
                .chainId(80002)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("80002");
    }
}
