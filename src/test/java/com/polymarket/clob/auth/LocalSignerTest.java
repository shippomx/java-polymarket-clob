package com.polymarket.clob.auth;

import com.polymarket.clob.exception.ClobAuthException;
import com.polymarket.clob.exception.ClobSignatureException;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalSignerTest {

    // Anvil 第一个账户（与 Rust 测试 vector 一致）
    private static final String PRIVATE_KEY =
            "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    private static final Address EXPECTED_ADDRESS =
            Address.fromHex("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266");

    @Test
    void derivesAddressFromPrivateKey() {
        LocalSigner s = LocalSigner.fromPrivateKey(PRIVATE_KEY);
        assertThat(s.address()).isEqualTo(EXPECTED_ADDRESS);
    }

    @Test
    void acceptsHexWithoutPrefix() {
        LocalSigner s = LocalSigner.fromPrivateKey(PRIVATE_KEY.substring(2));
        assertThat(s.address()).isEqualTo(EXPECTED_ADDRESS);
    }

    @Test
    void signsDigestTo65Bytes() {
        LocalSigner s = LocalSigner.fromPrivateKey(PRIVATE_KEY);
        byte[] digest = HexFormat.of().parseHex(
                "0000000000000000000000000000000000000000000000000000000000000001");
        byte[] sig = s.signHash(digest).join();
        assertThat(sig).hasSize(65);
        int v = sig[64] & 0xff;
        assertThat(v).isIn(27, 28);
    }

    @Test
    void rejectsShortPrivateKey() {
        assertThatThrownBy(() -> LocalSigner.fromPrivateKey("0xdead"))
                .isInstanceOf(ClobAuthException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void rejectsWrongDigestLength() {
        LocalSigner s = LocalSigner.fromPrivateKey(PRIVATE_KEY);
        assertThatThrownBy(() -> s.signHash(new byte[31]).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ClobSignatureException.class);
    }

    @Test
    void constructibleFromBigInteger() {
        BigInteger pk = new BigInteger(PRIVATE_KEY.substring(2), 16);
        LocalSigner s = LocalSigner.fromPrivateKey(pk);
        assertThat(s.address()).isEqualTo(EXPECTED_ADDRESS);
    }
}
