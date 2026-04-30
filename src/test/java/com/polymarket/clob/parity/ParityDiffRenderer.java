package com.polymarket.clob.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.zjsonpatch.JsonDiff;
import com.polymarket.clob.http.JsonCodec;

import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 把 expected ↔ actual {@link ParityRequestSnapshot} 渲染为 spec §8 定义的 4 个文本
 * 失败诊断 artifact：
 * <ul>
 *   <li>{@code summary.txt}：人类可读的"哪一段不一致" + likely-cause hints</li>
 *   <li>{@code headers.diff}：unified-style 头部差异（- expected / + actual）</li>
 *   <li>{@code body.json.diff}：RFC 6902 JSON Patch（zjsonpatch）</li>
 *   <li>{@code body.bytes.diff}：双边 hex 字面量（兜底；body 非 JSON 时唯一权威）</li>
 * </ul>
 *
 * <p>消费者：{@code ParityFixtureTest}（Task 14）在断言失败时把
 * {@link Result} 写到 {@code target/parity-diff/<id>/}。本类是纯函数，不做 IO。</p>
 *
 * <p><b>判等口径：</b>头部走 {@link HeaderNormalizer}（丢 transport 噪声），其余字段
 * 直接 {@link Objects#equals}；body 字节级以 {@code body_b64} 为权威，与文本路径一致。</p>
 */
public final class ParityDiffRenderer {

    private static final ObjectMapper MAPPER = JsonCodec.objectMapper();

    private ParityDiffRenderer() {}

    public static Result render(ParityRequestSnapshot expected, ParityRequestSnapshot actual) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");

        Map<String, List<String>> expHeaders = HeaderNormalizer.normalize(expected.headers());
        Map<String, List<String>> actHeaders = HeaderNormalizer.normalize(actual.headers());

        boolean kindEq = Objects.equals(expected.kind(), actual.kind());
        boolean methodEq = Objects.equals(expected.method(), actual.method());
        boolean urlEq = Objects.equals(expected.url(), actual.url());
        boolean wsChEq = Objects.equals(expected.wsChannel(), actual.wsChannel());
        boolean headersEq = expHeaders.equals(actHeaders);
        boolean bodyBytesEq = Objects.equals(expected.bodyB64(), actual.bodyB64());

        boolean identical = kindEq && methodEq && urlEq && wsChEq && headersEq && bodyBytesEq;

        return new Result(
                identical,
                renderSummary(expected, actual, kindEq, methodEq, urlEq, wsChEq, headersEq, bodyBytesEq, identical),
                renderHeadersDiff(expHeaders, actHeaders),
                renderBodyJsonDiff(expected.bodyText(), actual.bodyText()),
                renderBodyBytesDiff(expected.bodyB64(), actual.bodyB64()));
    }

    private static String renderSummary(ParityRequestSnapshot exp, ParityRequestSnapshot act,
                                        boolean kindEq, boolean methodEq, boolean urlEq,
                                        boolean wsChEq, boolean headersEq, boolean bodyBytesEq,
                                        boolean identical) {
        StringBuilder sb = new StringBuilder();
        if (identical) {
            sb.append("PARITY OK\n");
            sb.append("kind=").append(exp.kind());
            if (exp.method() != null) {
                sb.append(" method=").append(exp.method());
            }
            sb.append(" url=").append(exp.url()).append('\n');
            return sb.toString();
        }
        sb.append("PARITY MISMATCH\n");
        sb.append("kind          : ").append(badge(kindEq, exp.kind(), act.kind())).append('\n');
        sb.append("method        : ").append(badge(methodEq, exp.method(), act.method())).append('\n');
        sb.append("url           : ").append(badge(urlEq, exp.url(), act.url())).append('\n');
        sb.append("ws_channel    : ").append(badge(wsChEq, exp.wsChannel(), act.wsChannel())).append('\n');
        sb.append("headers       : ").append(headersEq ? "OK" : "MISMATCH").append('\n');
        sb.append("body          : ").append(bodyBytesEq ? "OK" : "MISMATCH").append('\n');
        sb.append("\nlikely-cause hints:\n");
        if (!bodyBytesEq) {
            sb.append("  - 检查签名输入字段顺序 / Order EIP-712 字段值 / float→string 精度 (BigDecimal)\n");
        }
        if (!headersEq) {
            sb.append("  - 检查 L1/L2 timestamp 是否走 frozen Clock；HMAC payload 是否含 query\n");
        }
        if (!urlEq) {
            sb.append("  - 检查 path / query 编码（pathSegment 是否漏调 / 排序是否对齐）\n");
        }
        if (!kindEq || !wsChEq) {
            sb.append("  - 检查 dispatcher kind 分派或 WS channel 路由是否对齐 spec §7.2\n");
        }
        return sb.toString();
    }

    private static String renderHeadersDiff(Map<String, List<String>> exp, Map<String, List<String>> act) {
        TreeSet<String> all = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        all.addAll(exp.keySet());
        all.addAll(act.keySet());
        StringBuilder sb = new StringBuilder();
        for (String k : all) {
            List<String> e = exp.get(k);
            List<String> a = act.get(k);
            if (Objects.equals(e, a)) {
                continue;
            }
            if (e != null) {
                sb.append("- ").append(k).append(": ").append(e).append('\n');
            }
            if (a != null) {
                sb.append("+ ").append(k).append(": ").append(a).append('\n');
            }
        }
        return sb.length() == 0 ? "(no header diff)\n" : sb.toString();
    }

    private static String renderBodyJsonDiff(String expBody, String actBody) {
        try {
            JsonNode e = isBlank(expBody) ? MAPPER.nullNode() : MAPPER.readTree(expBody);
            JsonNode a = isBlank(actBody) ? MAPPER.nullNode() : MAPPER.readTree(actBody);
            JsonNode patch = JsonDiff.asJson(e, a);
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(patch) + "\n";
        } catch (Exception ex) {
            return "(body is not valid JSON, see body.bytes.diff)\n";
        }
    }

    private static String renderBodyBytesDiff(String expB64, String actB64) {
        if (Objects.equals(expB64, actB64)) {
            return "(no body byte diff)\n";
        }
        byte[] e = decodeOrEmpty(expB64);
        byte[] a = decodeOrEmpty(actB64);
        return "- " + HexFormat.of().formatHex(e) + "\n+ " + HexFormat.of().formatHex(a) + "\n";
    }

    private static byte[] decodeOrEmpty(String b64) {
        if (b64 == null || b64.isEmpty()) {
            return new byte[0];
        }
        return Base64.getDecoder().decode(b64);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    private static String badge(boolean ok, String exp, String act) {
        return ok ? "OK (" + exp + ")"
                  : "MISMATCH expected=" + exp + " actual=" + act;
    }

    /**
     * 4 个 artifact 的渲染结果。{@code identical=true} 时三个 diff 字段也会被计算
     * （结果是占位文本），调用方可以无条件落盘。
     */
    public record Result(
            boolean identical,
            String summary,
            String headersDiff,
            String bodyJsonDiff,
            String bodyBytesDiff) {
    }
}
