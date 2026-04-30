package com.polymarket.clob.http;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 测试专用：整个测试 JVM 共享一个 {@link HttpClient}。
 *
 * <p>JDK 17 的 {@link HttpClient} 没有 {@code close()}，每个测试新建一个会持续累积
 * selector 线程（表现为 {@code HttpClient-*-SelectorManager} / {@code Worker}）。
 * 测试量不大时没问题，但 CI 下并行跑模块时容易命中 fd / 线程上限。
 * 整个进程共享一个实例足以覆盖所有只读单测场景。</p>
 *
 * <p>连接超时设 2s：WireMock 在同进程里监听 loopback，连接应当瞬间完成；
 * 2s 足够覆盖慢机器上的 TLS/握手冷启动。</p>
 */
public final class SharedHttpClient {

    public static final HttpClient INSTANCE = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private SharedHttpClient() {}
}
