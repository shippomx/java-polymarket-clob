package com.polymarket.clob.http;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * HTTP 出站请求拦截 hook。<b>仅供 parity 测试使用</b>，生产路径默认走 {@link #NOOP}。
 *
 * <p>在 {@link java.net.http.HttpClient#sendAsync} 之前同步调用，body 为已 resolve
 * 的完整 bytes（含 L2 HMAC 签的那一份），即"签的就是发的"。</p>
 */
@FunctionalInterface
public interface RequestCaptor {

    void capture(String method, URI uri, Map<String, List<String>> headers, byte[] body);

    RequestCaptor NOOP = (m, u, h, b) -> {};
}
