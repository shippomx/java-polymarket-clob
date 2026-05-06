package com.polymarket.clob.chain;

import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Hash;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class PolymarketContractsTest {

    @Test
    void factoryAddressMatchesSpec() {
        assertThat(PolymarketContracts.FACTORY)
                .isEqualTo(Address.fromHex("0x00000000000Fb5C9ADea0298D729A0CB3823Cc07"));
    }

    @Test
    void implementationAddressMatchesSpec() {
        assertThat(PolymarketContracts.IMPLEMENTATION)
                .isEqualTo(Address.fromHex("0x58CA52ebe0DadfdF531Cde7062e76746de4Db1eB"));
    }

    @Test
    void orderTypeHashEqualsKeccakOfTypeString() {
        byte[] expected = Hash.sha3(PolymarketContracts.ORDER_TYPE_STRING.getBytes(StandardCharsets.US_ASCII));
        assertThat(PolymarketContracts.ORDER_TYPE_HASH).isEqualTo(expected);
    }

    @Test
    void orderTypeStringStartsWithOrder() {
        assertThat(PolymarketContracts.ORDER_TYPE_STRING).startsWith("Order(");
    }

    @Test
    void typedDataSignTypeStringStartsWithTypedDataSign() {
        assertThat(PolymarketContracts.TYPED_DATA_SIGN_TYPE_STRING).startsWith("TypedDataSign(");
    }

    @Test
    void thirteenSpenderAddressesAllPresent() {
        assertThat(PolymarketContracts.USDC_E).isNotNull();
        assertThat(PolymarketContracts.USDC_NATIVE).isNotNull();
        assertThat(PolymarketContracts.CTF).isNotNull();
        assertThat(PolymarketContracts.EXCHANGE_V2).isNotNull();
        assertThat(PolymarketContracts.NEG_RISK_EXCHANGE_V2).isNotNull();
        assertThat(PolymarketContracts.NEG_RISK_ADAPTER).isNotNull();
        assertThat(PolymarketContracts.PUSD_QUOTER).isNotNull();
        assertThat(PolymarketContracts.NEW_SPENDER_A).isNotNull();
        assertThat(PolymarketContracts.NEW_SPENDER_B).isNotNull();
        assertThat(PolymarketContracts.PARLAY).isNotNull();
    }
}
