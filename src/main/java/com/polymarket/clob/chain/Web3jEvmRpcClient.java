package com.polymarket.clob.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.clob.http.JsonCodec;
import com.polymarket.clob.model.Address;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EvmRpcClient 默认实现：JSON-RPC over HTTP，使用 JDK HttpClient + Jackson。
 * 不依赖 web3j HttpService，避免 web3j 全套对象图。
 */
public final class Web3jEvmRpcClient implements EvmRpcClient {

    private static final HexFormat HEX = HexFormat.of();

    private final URI endpoint;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final AtomicLong idSeq = new AtomicLong(1);

    public Web3jEvmRpcClient(URI endpoint) {
        this(endpoint, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public Web3jEvmRpcClient(URI endpoint, HttpClient http) {
        this.endpoint = endpoint;
        this.http = http;
        this.mapper = JsonCodec.objectMapper();
    }

    @Override
    public CompletableFuture<byte[]> ethCall(Address to, byte[] callData) {
        String body = """
                {"jsonrpc":"2.0","id":%d,"method":"eth_call","params":[{"to":"%s","data":"0x%s"},"latest"]}"""
                .formatted(idSeq.getAndIncrement(), to.toLowerHex(), HEX.formatHex(callData));
        return send(body, "eth_call");
    }

    @Override
    public CompletableFuture<byte[]> getCode(Address addr) {
        String body = """
                {"jsonrpc":"2.0","id":%d,"method":"eth_getCode","params":["%s","latest"]}"""
                .formatted(idSeq.getAndIncrement(), addr.toLowerHex());
        return send(body, "eth_getCode");
    }

    private CompletableFuture<byte[]> send(String body, String method) {
        HttpRequest req = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(resp -> {
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new EvmRpcException(method + " HTTP " + resp.statusCode() + ": " + resp.body());
            }
            try {
                JsonNode root = mapper.readTree(resp.body());
                JsonNode error = root.get("error");
                if (error != null && !error.isNull()) {
                    String msg = error.path("message").asText("rpc error");
                    throw new EvmRpcException(method + " rpc error: " + msg);
                }
                JsonNode resultNode = root.get("result");
                if (resultNode == null || resultNode.isNull()) {
                    throw new EvmRpcException(method + " response missing 'result' field");
                }
                String hex = resultNode.asText("");
                if (!hex.startsWith("0x")) {
                    throw new EvmRpcException(method + " result not 0x-prefixed: " + hex);
                }
                if (hex.length() == 2) return new byte[0];
                return HEX.parseHex(hex.substring(2));
            } catch (EvmRpcException e) {
                throw e;
            } catch (Exception e) {
                throw new EvmRpcException(method + " parse failure: " + e.getClass().getSimpleName()
                        + (e.getMessage() != null ? ": " + e.getMessage() : ""), e);
            }
        });
    }
}
