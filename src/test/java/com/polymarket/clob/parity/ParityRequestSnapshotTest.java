package com.polymarket.clob.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.clob.http.JsonCodec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 {@link ParityRequestSnapshot} 序列化为 Rust oracle ↔ Java 共用的中性 schema。
 *
 * <p>schema 关键字段：{@code kind} / {@code method} / {@code url} / {@code ws_channel} /
 * {@code headers} / {@code body_text} / {@code body_b64}；HTTP 与 WS 共用 record，
 * 由 {@code kind} 判别哪些字段权威。null 字段被 Jackson 省略，不出现在输出 JSON 里。</p>
 */
class ParityRequestSnapshotTest {

    private final ObjectMapper mapper = JsonCodec.objectMapper();

    @Test
    void httpSnapshotSerializesToCanonicalShape() throws Exception {
        byte[] body = "{\"k\":\"v\"}".getBytes(StandardCharsets.UTF_8);
        ParityRequestSnapshot snap = ParityRequestSnapshot.http(
                "POST",
                "https://clob.polymarket.com/order",
                Map.of("Content-Type", List.of("application/json"),
                        "POLY_SIGNATURE", List.of("abc")),
                body);

        String json = mapper.writeValueAsString(snap);
        JsonNode root = mapper.readTree(json);

        assertThat(root.get("kind").asText()).isEqualTo("http");
        assertThat(root.get("method").asText()).isEqualTo("POST");
        assertThat(root.get("url").asText()).isEqualTo("https://clob.polymarket.com/order");
        assertThat(root.get("headers").get("Content-Type").get(0).asText())
                .isEqualTo("application/json");
        assertThat(root.get("headers").get("POLY_SIGNATURE").get(0).asText()).isEqualTo("abc");
        assertThat(root.get("body_text").asText()).isEqualTo("{\"k\":\"v\"}");
        assertThat(root.get("body_b64").asText())
                .isEqualTo(Base64.getEncoder().encodeToString(body));
        assertThat(root.has("ws_channel")).isFalse();
    }

    @Test
    void wsSnapshotSerializesToCanonicalShape() throws Exception {
        ParityRequestSnapshot snap = ParityRequestSnapshot.ws(
                "market",
                "wss://ws-subscriptions-clob.polymarket.com/ws/market",
                "{\"type\":\"market\",\"assets_ids\":[\"123\"]}");

        String json = mapper.writeValueAsString(snap);
        JsonNode root = mapper.readTree(json);

        assertThat(root.get("kind").asText()).isEqualTo("ws");
        assertThat(root.get("ws_channel").asText()).isEqualTo("market");
        assertThat(root.get("url").asText())
                .isEqualTo("wss://ws-subscriptions-clob.polymarket.com/ws/market");
        assertThat(root.get("body_text").asText())
                .isEqualTo("{\"type\":\"market\",\"assets_ids\":[\"123\"]}");
        assertThat(root.has("method")).isFalse();
        assertThat(root.has("headers")).isFalse();
    }

    @Test
    void httpSnapshotHeadersAreCaseInsensitiveSorted() throws Exception {
        // 按字典序 + 大小写不敏感排序，保证与 Rust oracle 输出 key 顺序一致
        ParityRequestSnapshot snap = ParityRequestSnapshot.http(
                "GET",
                "https://example.invalid/x",
                Map.of("zeta", List.of("3"),
                        "Alpha", List.of("1"),
                        "beta", List.of("2")),
                new byte[0]);

        String json = mapper.writeValueAsString(snap);
        // headers 块内 key 顺序 = Alpha < beta < zeta（CASE_INSENSITIVE_ORDER）
        int alphaIdx = json.indexOf("\"Alpha\"");
        int betaIdx = json.indexOf("\"beta\"");
        int zetaIdx = json.indexOf("\"zeta\"");
        assertThat(alphaIdx).isPositive();
        assertThat(alphaIdx).isLessThan(betaIdx);
        assertThat(betaIdx).isLessThan(zetaIdx);
    }

    @Test
    void httpSnapshotNullBodyTreatedAsEmpty() throws Exception {
        ParityRequestSnapshot snap = ParityRequestSnapshot.http(
                "GET",
                "https://example.invalid/x",
                Map.of(),
                null);

        String json = mapper.writeValueAsString(snap);
        JsonNode root = mapper.readTree(json);
        assertThat(root.get("body_text").asText()).isEmpty();
        assertThat(root.get("body_b64").asText()).isEmpty();
    }
}
