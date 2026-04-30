package com.polymarket.clob.parity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 仅做"static 初始化不抛错 + signer 可派生"的 sanity 验证。
 *
 * <p>真实使用走 fixture loader / oracle 链路，那里若签名出错会立刻暴露。</p>
 */
class FixedTestKeysTest {

    @Test
    void apiCredentialsConstructAndExposeFields() {
        assertThat(FixedTestKeys.API_CREDENTIALS.apiKey()).isEqualTo(FixedTestKeys.API_KEY);
        assertThat(FixedTestKeys.API_CREDENTIALS.secret()).isEqualTo(FixedTestKeys.API_SECRET);
        assertThat(FixedTestKeys.API_CREDENTIALS.passphrase()).isEqualTo(FixedTestKeys.API_PASSPHRASE);
    }

    @Test
    void signerDerivesHardhatAccount0Address() {
        // Hardhat account #0 公开地址：0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266
        assertThat(FixedTestKeys.signer().address())
                .isEqualTo(FixedTestKeys.ADDRESS);
    }

    @Test
    void privateKeyAndAddressAreNonNull() {
        assertThat(FixedTestKeys.PRIVATE_KEY_HEX).startsWith("0x").hasSize(66);
        assertThat(FixedTestKeys.ADDRESS).isNotNull();
        assertThat(FixedTestKeys.DEFAULT_SIGNATURE_TYPE).isNotNull();
    }
}
