package com.polymarket.clob.auth;

import com.polymarket.clob.exception.ClobAuthException;
import com.polymarket.clob.model.Address;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 构造调用需 API Key 认证的 CLOB 端点所需的 L2 请求头。
 *
 * <p>与 Rust {@code rs-clob-client/src/auth.rs} 的 {@code l2::create_headers} 一一对应：
 * <ul>
 *   <li>消息体 = {@code timestamp + method + path + body}（body 空字符串即可）。</li>
 *   <li>Secret 是 URL-safe Base64（带 {@code =} padding）编码的 HMAC key；使用前需 base64 解码。</li>
 *   <li>POLY_SIGNATURE = {@code base64UrlSafe(HMAC-SHA256(decodedSecret, message))}（带 padding）。</li>
 *   <li>POLY_ADDRESS 使用小写 hex（非 EIP-55 checksum）。</li>
 * </ul>
 * </p>
 *
 * <p>构建结果用 {@link LinkedHashMap} 保留固定顺序，便于日志排查与对比 Rust 客户端。</p>
 */
public final class L2HeaderBuilder {

    public static final String POLY_ADDRESS = "POLY_ADDRESS";
    public static final String POLY_API_KEY = "POLY_API_KEY";
    public static final String POLY_PASSPHRASE = "POLY_PASSPHRASE";
    public static final String POLY_SIGNATURE = "POLY_SIGNATURE";
    public static final String POLY_TIMESTAMP = "POLY_TIMESTAMP";

    private L2HeaderBuilder() {}

    /**
     * @param caller       请求人 EOA
     * @param credentials  API Key 三元组
     * @param method       HTTP method（大写，如 {@code "GET"} / {@code "POST"}）
     * @param path         请求 path（含前导 {@code /}，不含 query）
     * @param body         请求体字符串；GET/无 body 时传 {@code ""}
     * @param timestamp    Unix 秒（与服务端约定一致的 {@code Timestamp}）
     */
    public static Map<String, String> build(
            Address caller,
            ApiCredentials credentials,
            String method,
            String path,
            String body,
            long timestamp) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(credentials, "credentials");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        String safeBody = body == null ? "" : body.replace('\'', '"');

        String message = timestamp + method + path + safeBody;
        String signature = hmac(credentials.secret(), message);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(POLY_ADDRESS, caller.toLowerHex());
        headers.put(POLY_API_KEY, credentials.apiKey());
        headers.put(POLY_PASSPHRASE, credentials.passphrase());
        headers.put(POLY_SIGNATURE, signature);
        headers.put(POLY_TIMESTAMP, Long.toString(timestamp));
        return headers;
    }

    /**
     * 以 URL-safe Base64 解码 secret，做 HMAC-SHA256，再 URL-safe Base64（保留 {@code =} padding）输出。
     *
     * <p>对外可见：L2 与 Builder 签名算法共享同一实现；为避免子包再复制一份，升级为 public。
     * secret 若不是合法 base64url 或任何步骤失败，均包装为 {@link ClobAuthException}。</p>
     */
    public static String hmac(String base64UrlSecret, String message) {
        byte[] key;
        try {
            key = Base64.getUrlDecoder().decode(base64UrlSecret);
        } catch (IllegalArgumentException e) {
            throw new ClobAuthException("API secret is not valid URL-safe base64", e);
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] out = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new ClobAuthException("Failed to compute HMAC-SHA256 for L2 header", e);
        }
    }
}
