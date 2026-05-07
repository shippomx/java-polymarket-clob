package com.polymarket.clob.order;

import com.polymarket.clob.chain.PolymarketContracts;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Hash;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class Pol1271AppDomainSepTest {

    @Test
    void exchangeV2DomainSepMatchesManual() {
        byte[] expected = manual(137, PolymarketContracts.EXCHANGE_V2.toBytes());
        assertThat(Pol1271OrderSigner.appDomainSeparator(137, false)).containsExactly(expected);
    }

    @Test
    void negRiskDomainSepMatchesManual() {
        byte[] expected = manual(137, PolymarketContracts.NEG_RISK_EXCHANGE_V2.toBytes());
        assertThat(Pol1271OrderSigner.appDomainSeparator(137, true)).containsExactly(expected);
    }

    @Test
    void negRiskAndPlainDiffer() {
        assertThat(Pol1271OrderSigner.appDomainSeparator(137, false))
                .isNotEqualTo(Pol1271OrderSigner.appDomainSeparator(137, true));
    }

    @Test
    void cachedAcrossCalls() {
        byte[] a = Pol1271OrderSigner.appDomainSeparator(137, false);
        byte[] b = Pol1271OrderSigner.appDomainSeparator(137, false);
        // 同实例（缓存）—— 比对引用相等
        assertThat(a).isSameAs(b);
    }

    private static byte[] manual(long chainId, byte[] verifyingContract) {
        String typeStr = "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)";
        byte[] typeHash = Hash.sha3(typeStr.getBytes(StandardCharsets.US_ASCII));

        byte[] nameHash = Hash.sha3(PolymarketContracts.CTF_EXCHANGE_V2_DOMAIN_NAME
                .getBytes(StandardCharsets.UTF_8));
        byte[] versionHash = Hash.sha3(PolymarketContracts.CTF_EXCHANGE_V2_DOMAIN_VERSION
                .getBytes(StandardCharsets.UTF_8));

        byte[] chainIdPad = new byte[32];
        byte[] cidRaw = BigInteger.valueOf(chainId).toByteArray();
        System.arraycopy(cidRaw, 0, chainIdPad, 32 - cidRaw.length, cidRaw.length);

        byte[] vcPad = new byte[32];
        System.arraycopy(verifyingContract, 0, vcPad, 12, 20);

        ByteBuffer buf = ByteBuffer.allocate(32 * 5);
        buf.put(typeHash);
        buf.put(nameHash);
        buf.put(versionHash);
        buf.put(chainIdPad);
        buf.put(vcPad);
        return Hash.sha3(buf.array());
    }
}
