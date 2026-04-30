package com.polymarket.clob.ws.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.clob.auth.ApiCredentials;
import com.polymarket.clob.http.JsonCodec;
import com.polymarket.clob.model.Hash32;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SubscriptionRequest} 序列化对齐 Polymarket WS 协议的核心测试。
 *
 * <p>关注点：</p>
 * <ul>
 *   <li>wire 字段名 {@code type} / {@code assets_ids}（注意复数）</li>
 *   <li>{@link Operation} / {@link Channel} 全部小写</li>
 *   <li>未设置的 optional 字段（{@code initial_dump} / {@code custom_feature_enabled}）
 *       不会写入输出，避免上游 strict 解析时报错</li>
 *   <li>user channel envelope 注入 {@code auth} 三元组</li>
 * </ul>
 */
class SubscriptionRequestTest {

    private static final ObjectMapper MAPPER = JsonCodec.objectMapper();

    private static final BigInteger TOKEN_A =
            new BigInteger("106585164761922456203746651621390029417453862034640469075081961934906147433548");
    private static final Hash32 MARKET_A = Hash32.fromHex(
            "0x0000000000000000000000000000000000000000000000000000000000000001");
    private static final ApiCredentials CREDS = new ApiCredentials(
            "00000000-0000-0000-0000-000000000000",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "passphrase-value");

    @Test
    void marketSubscribeJsonShape() throws Exception {
        SubscriptionRequest req = SubscriptionRequest.market(List.of(TOKEN_A), true);
        String json = req.toJson(MAPPER);
        JsonNode node = MAPPER.readTree(json);

        assertThat(node.get("type").asText()).isEqualTo("market");
        assertThat(node.get("operation").asText()).isEqualTo("subscribe");
        // 注意是复数 assets_ids；Polymarket 协议怪癖。
        assertThat(node.get("assets_ids").isArray()).isTrue();
        // U256 token id 必须输出为 JSON string，不能是 number——避免解析方丢精度。
        assertThat(node.get("assets_ids").get(0).isTextual()).isTrue();
        assertThat(node.get("assets_ids").get(0).asText()).isEqualTo(TOKEN_A.toString());
        // 空 markets 列表也参与序列化（与 Rust 行为一致：non-skip）。
        assertThat(node.get("markets").isArray()).isTrue();
        assertThat(node.get("markets")).isEmpty();
        assertThat(node.get("initial_dump").asBoolean()).isTrue();
        assertThat(node.has("custom_feature_enabled")).isFalse();
        assertThat(node.has("auth")).isFalse();
    }

    @Test
    void marketUnsubscribeOmitsInitialDump() throws Exception {
        SubscriptionRequest req = SubscriptionRequest.marketUnsubscribe(List.of(TOKEN_A));
        JsonNode node = MAPPER.readTree(req.toJson(MAPPER));
        assertThat(node.get("operation").asText()).isEqualTo("unsubscribe");
        assertThat(node.has("initial_dump")).isFalse();
    }

    @Test
    void userSubscribeWritesMarketsHexLowercase() throws Exception {
        SubscriptionRequest req = SubscriptionRequest.user(List.of(MARKET_A));
        JsonNode node = MAPPER.readTree(req.toJson(MAPPER));

        assertThat(node.get("type").asText()).isEqualTo("user");
        assertThat(node.get("markets").get(0).asText()).isEqualTo(MARKET_A.toHex());
        // user 默认不携带 assets_ids（空数组）。
        assertThat(node.get("assets_ids")).isEmpty();
        assertThat(node.get("initial_dump").asBoolean()).isTrue();
    }

    @Test
    void withCustomFeaturesIsImmutableAndAddsFlag() throws Exception {
        SubscriptionRequest base = SubscriptionRequest.market(List.of(TOKEN_A), true);
        SubscriptionRequest enabled = base.withCustomFeatures(true);

        // 不可变：原对象不变
        assertThat(base.customFeatureEnabled()).isNull();
        assertThat(enabled.customFeatureEnabled()).isTrue();

        JsonNode node = MAPPER.readTree(enabled.toJson(MAPPER));
        assertThat(node.get("custom_feature_enabled").asBoolean()).isTrue();
    }

    @Test
    void toAuthenticatedJsonInjectsAuthEnvelope() throws Exception {
        SubscriptionRequest req = SubscriptionRequest.user(List.of(MARKET_A));
        String json = req.toAuthenticatedJson(MAPPER, CREDS);
        JsonNode node = MAPPER.readTree(json);

        // 业务字段
        assertThat(node.get("type").asText()).isEqualTo("user");
        assertThat(node.get("markets").get(0).asText()).isEqualTo(MARKET_A.toHex());
        // auth 字段对齐 Rust：apiKey / secret / passphrase
        assertThat(node.get("auth").get("apiKey").asText()).isEqualTo(CREDS.apiKey());
        assertThat(node.get("auth").get("secret").asText()).isEqualTo(CREDS.secret());
        assertThat(node.get("auth").get("passphrase").asText()).isEqualTo(CREDS.passphrase());
    }

    @Test
    void toJsonRequiresMapper() {
        SubscriptionRequest req = SubscriptionRequest.market(List.of(TOKEN_A), true);
        assertThatNullPointerException().isThrownBy(() -> req.toJson(null));
        assertThatNullPointerException().isThrownBy(() -> req.toAuthenticatedJson(null, CREDS));
        assertThatNullPointerException().isThrownBy(() -> req.toAuthenticatedJson(MAPPER, null));
    }

    @Test
    void factoryRejectsNullChannel() {
        assertThatThrownBy(() -> new SubscriptionRequest(
                null, Operation.SUBSCRIBE, List.of(), List.of(), null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("type");
    }

    @Test
    void recordCopiesMutableInputs() {
        // 入参 List 修改后不应影响 record；规范构造器走 List.copyOf。
        java.util.List<BigInteger> mutable = new java.util.ArrayList<>();
        mutable.add(TOKEN_A);
        SubscriptionRequest req = SubscriptionRequest.market(mutable, true);
        mutable.clear();
        assertThat(req.assetIds()).containsExactly(TOKEN_A);
    }
}
