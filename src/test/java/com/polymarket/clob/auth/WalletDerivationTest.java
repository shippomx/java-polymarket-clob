package com.polymarket.clob.auth;

import com.polymarket.clob.model.Address;
import com.polymarket.clob.model.ChainId;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WalletDerivationTest {

    // Anvil 账户 0 —— 同时也是 Rust crate 用于 derive_*_wallet 的 fixture
    private static final Address EOA =
            Address.fromHex("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266");

    private static final Address EXPECTED_SAFE =
            Address.fromHex("0xd93b25Cb943D14d0d34FBAf01fc93a0F8b5f6e47");

    private static final Address EXPECTED_PROXY_POLYGON =
            Address.fromHex("0x365f0cA36ae1F641E02Fe3b7743673DA42A13a70");

    @Test
    void polygonSafeMatchesRustFixture() {
        Optional<Address> addr = WalletDerivation.deriveSafeWallet(EOA, ChainId.POLYGON);
        assertThat(addr).contains(EXPECTED_SAFE);
    }

    @Test
    void amoySafeMatchesPolygon() {
        Optional<Address> addr = WalletDerivation.deriveSafeWallet(EOA, ChainId.AMOY);
        assertThat(addr).contains(EXPECTED_SAFE);
    }

    @Test
    void polygonProxyMatchesRustFixture() {
        Optional<Address> addr = WalletDerivation.deriveProxyWallet(EOA, ChainId.POLYGON);
        assertThat(addr).contains(EXPECTED_PROXY_POLYGON);
    }

    @Test
    void amoyProxyUnsupported() {
        Optional<Address> addr = WalletDerivation.deriveProxyWallet(EOA, ChainId.AMOY);
        assertThat(addr).isEmpty();
    }

    @Test
    void unknownChainReturnsEmpty() {
        assertThat(WalletDerivation.deriveProxyWallet(EOA, 42L)).isEmpty();
        assertThat(WalletDerivation.deriveSafeWallet(EOA, 42L)).isEmpty();
    }

    @Test
    void deriveFunderEoaReturnsSelf() {
        assertThat(WalletDerivation.deriveFunder(SignatureType.EOA, EOA, ChainId.POLYGON))
                .contains(EOA);
    }

    @Test
    void deriveFunderProxyOnPolygon() {
        assertThat(WalletDerivation.deriveFunder(
                SignatureType.POLY_PROXY, EOA, ChainId.POLYGON))
                .contains(EXPECTED_PROXY_POLYGON);
    }

    @Test
    void deriveFunderSafe() {
        assertThat(WalletDerivation.deriveFunder(
                SignatureType.POLY_GNOSIS_SAFE, EOA, ChainId.POLYGON))
                .contains(EXPECTED_SAFE);
    }
}
