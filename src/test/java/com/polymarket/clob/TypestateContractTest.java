package com.polymarket.clob;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Typestate "编译期防错" 的运行时快照。
 *
 * <p>Java 不支持 Rust-style 的 "所有权消费"，所以三类型客户端之间真正的防错依赖
 * <b>public 方法表面</b>：
 * <ul>
 *   <li>{@link ClobClient}（未认证） — 只有 market 只读能力 + authenticate；</li>
 *   <li>{@link AuthenticatedClobClient}（L2 认证） — 全部 L1/L2 接口 + {@code promoteToBuilder}；</li>
 *   <li>{@link BuilderClobClient}（Builder 认证） — L2 接口 + {@code *BuilderApiKey} / {@code getBuilderTrades}。</li>
 * </ul>
 *
 * <p>如果有人把 {@code cancelOrder} 等受保护方法错误地暴露到 {@link ClobClient} 上，
 * 这里会红；同理防止 Builder 专属方法泄露到 {@link AuthenticatedClobClient}。</p>
 *
 * <p>失败修复指引：方法名变动要同步这里的白名单/黑名单。</p>
 */
class TypestateContractTest {

    @Test
    @DisplayName("ClobClient 表面不应出现已认证 / Builder 专属方法")
    void clobClientSurfaceIsReadonly() {
        Set<String> methods = publicMethodNames(ClobClient.class);

        // 必须有的入口
        assertThat(methods).contains("authenticate", "market", "builder", "close");

        // 不应出现的 — 认证态专属
        assertThat(methods).doesNotContain(
                "auth", "account", "order", "trade", "heartbeat",
                "cancelOrder", "cancelOrders", "cancelAll", "cancelMarketOrders",
                "postOrder", "postOrders",
                "createAndPostLimitOrder", "createAndPostMarketOrder",
                "balanceAllowance", "closedOnlyMode",
                "getOrder", "getOpenOrders", "getTrades",
                "isOrderScoring", "areOrdersScoring",
                "postHeartbeat", "startHeartbeats",
                "promoteToBuilder",
                // Builder 专属
                "getBuilderTrades", "listBuilderApiKeys", "revokeBuilderApiKey", "createBuilderApiKey"
        );
    }

    @Test
    @DisplayName("AuthenticatedClobClient 具备全部 L2 能力，但不应出现 Builder 专属方法")
    void authenticatedClientHasL2ButNoBuilder() {
        Set<String> methods = publicMethodNames(AuthenticatedClobClient.class);

        // L2 能力白名单
        assertThat(methods).contains(
                "auth", "account", "order", "trade", "heartbeat", "builder",
                "market", "orderBuilder",
                "postOrder", "postOrders",
                "createAndPostLimitOrder", "createAndPostMarketOrder",
                "cancelOrder", "cancelOrders", "cancelAll", "cancelMarketOrders",
                "getOrder", "getOpenOrders", "getTrades",
                "isOrderScoring", "areOrdersScoring",
                "balanceAllowance", "closedOnlyMode",
                "postHeartbeat", "startHeartbeats",
                "promoteToBuilder"
        );

        // Builder 专属 — 不应该在这一层暴露
        assertThat(methods).doesNotContain(
                "getBuilderTrades", "listBuilderApiKeys",
                "revokeBuilderApiKey", "createBuilderApiKey");
    }

    @Test
    @DisplayName("BuilderClobClient 在 L2 能力基础上叠加 Builder 专属方法")
    void builderClientAddsBuilderSurface() {
        Set<String> methods = publicMethodNames(BuilderClobClient.class);

        // L2 能力（通过 delegate 透传）
        assertThat(methods).contains(
                "authenticated", "market", "auth", "account", "order",
                "trade", "heartbeat", "builder", "orderBuilder",
                "cancelOrder", "cancelAll", "cancelMarketOrders",
                "postOrder", "postOrders",
                "getOrder", "getOpenOrders", "getTrades",
                "isOrderScoring", "areOrdersScoring",
                "balanceAllowance", "closedOnlyMode",
                "postHeartbeat", "startHeartbeats");

        // Builder 专属
        assertThat(methods).contains(
                "getBuilderTrades",
                "listBuilderApiKeys",
                "revokeBuilderApiKey",
                "createBuilderApiKey",
                "builderConfig",
                "headerBuilder");

        // 反向：Builder 态不应再暴露"继续升级"的能力
        assertThat(methods).doesNotContain("promoteToBuilder", "authenticate");
    }

    @Test
    @DisplayName("三类型共同实现 AutoCloseable，保障 try-with-resources")
    void allClientsAreAutoCloseable() {
        assertThat(AutoCloseable.class).isAssignableFrom(ClobClient.class);
        assertThat(AutoCloseable.class).isAssignableFrom(AuthenticatedClobClient.class);
        assertThat(AutoCloseable.class).isAssignableFrom(BuilderClobClient.class);
    }

    private static Set<String> publicMethodNames(Class<?> type) {
        return Arrays.stream(type.getMethods())
                .filter(m -> m.getDeclaringClass() != Object.class)
                .map(Method::getName)
                .collect(Collectors.toSet());
    }
}
