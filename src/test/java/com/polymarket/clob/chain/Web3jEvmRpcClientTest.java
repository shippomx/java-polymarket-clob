package com.polymarket.clob.chain;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.polymarket.clob.model.Address;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HexFormat;
import java.util.concurrent.ExecutionException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Web3jEvmRpcClientTest {

    private WireMockServer server;
    private Web3jEvmRpcClient client;

    @BeforeEach
    void start() {
        server = new WireMockServer(0);
        server.start();
        client = new Web3jEvmRpcClient(URI.create(server.baseUrl()));
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void ethCallReturnsResultBytes() throws ExecutionException, InterruptedException {
        server.stubFor(post("/").willReturn(okJson(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x000000000000000000000000ada4563a6738215c56d2b59bc1c5a1db65b1fd78\"}")));

        byte[] result = client.ethCall(
                Address.fromHex("0x00000000000Fb5C9ADea0298D729A0CB3823Cc07"),
                HexFormat.of().parseHex("aabbccdd")).get();

        assertThat(result).hasSize(32);
        assertThat(HexFormat.of().formatHex(result))
                .endsWith("ada4563a6738215c56d2b59bc1c5a1db65b1fd78");
    }

    @Test
    void getCodeReturnsBytecode() throws ExecutionException, InterruptedException {
        server.stubFor(post("/").willReturn(okJson(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x6080604052\"}")));

        byte[] code = client.getCode(Address.fromHex("0xada4563a6738215c56d2b59bc1c5a1db65b1fd78")).get();

        assertThat(code).containsExactly(0x60, (byte) 0x80, 0x60, 0x40, 0x52);
    }

    @Test
    void getCodeReturnsEmptyForUndeployed() throws ExecutionException, InterruptedException {
        server.stubFor(post("/").willReturn(okJson(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x\"}")));

        byte[] code = client.getCode(Address.fromHex("0x0000000000000000000000000000000000000001")).get();

        assertThat(code).isEmpty();
    }

    @Test
    void rpcErrorThrowsException() {
        server.stubFor(post("/").willReturn(okJson(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"execution reverted\"}}")));

        assertThatThrownBy(() -> client.ethCall(Address.ZERO, new byte[0]).get())
                .hasCauseInstanceOf(EvmRpcException.class)
                .hasMessageContaining("execution reverted");
    }

    @Test
    void httpErrorThrowsException() {
        server.stubFor(post("/").willReturn(serverError()));

        assertThatThrownBy(() -> client.getCode(Address.ZERO).get())
                .hasCauseInstanceOf(EvmRpcException.class);
    }
}
