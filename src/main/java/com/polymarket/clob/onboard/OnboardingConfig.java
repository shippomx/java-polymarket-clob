package com.polymarket.clob.onboard;

import com.polymarket.clob.model.ContractRegistry;
import com.polymarket.clob.order.SaltSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

public record OnboardingConfig(
        long chainId,
        URI gammaHost,
        URI relayerHost,
        URI clobHost,
        URI rpcUrl,
        Duration relayerPollInterval,
        int relayerMaxAttempts,
        HttpClient httpClient,
        SaltSource saltSource,
        Optional<TestOrderArgs> testOrder
) {

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private long chainId = 137L;
        private URI gammaHost   = URI.create("https://gamma-api.polymarket.com");
        private URI relayerHost = URI.create("https://relayer-v2.polymarket.com");
        private URI clobHost    = URI.create("https://clob.polymarket.com");
        private URI rpcUrl;
        private Duration relayerPollInterval = Duration.ofSeconds(2);
        private int relayerMaxAttempts = 90;
        private HttpClient httpClient;
        private SaltSource saltSource;
        private Optional<TestOrderArgs> testOrder = Optional.empty();

        public Builder chainId(long v) { this.chainId = v; return this; }
        public Builder gammaHost(URI v) { this.gammaHost = v; return this; }
        public Builder relayerHost(URI v) { this.relayerHost = v; return this; }
        public Builder clobHost(URI v) { this.clobHost = v; return this; }
        public Builder rpcUrl(URI v) { this.rpcUrl = v; return this; }
        public Builder relayerPollInterval(Duration v) { this.relayerPollInterval = v; return this; }
        public Builder relayerMaxAttempts(int v) { this.relayerMaxAttempts = v; return this; }
        public Builder httpClient(HttpClient v) { this.httpClient = v; return this; }
        public Builder saltSource(SaltSource v) { this.saltSource = v; return this; }
        public Builder testOrder(TestOrderArgs v) { this.testOrder = Optional.ofNullable(v); return this; }

        public OnboardingConfig build() {
            if (rpcUrl == null) throw new IllegalStateException("rpcUrl is required");
            if (ContractRegistry.depositWalletConfig(chainId).isEmpty()) {
                throw new IllegalStateException("DepositWallet not deployed on chainId " + chainId);
            }
            return new OnboardingConfig(chainId, gammaHost, relayerHost, clobHost, rpcUrl,
                    relayerPollInterval, relayerMaxAttempts, httpClient, saltSource, testOrder);
        }
    }
}
