package com.polymarket.clob.parity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 {@link ParityDiffRenderer} 渲染 spec §8 的 4 个失败诊断 artifact：
 * {@code summary.txt} / {@code headers.diff} / {@code body.json.diff} /
 * {@code body.bytes.diff}。
 */
class ParityDiffRendererTest {

    @Test
    void identicalSnapshotsProduceEmptyDiff() {
        ParityRequestSnapshot a = ParityRequestSnapshot.http(
                "POST", "https://example.com/x",
                Map.of("Content-Type", List.of("application/json")),
                "{\"k\":\"v\"}".getBytes());
        ParityDiffRenderer.Result r = ParityDiffRenderer.render(a, a);
        assertThat(r.identical()).isTrue();
        assertThat(r.summary()).contains("PARITY OK");
        assertThat(r.headersDiff()).contains("(no header diff)");
        assertThat(r.bodyBytesDiff()).contains("(no body byte diff)");
    }

    @Test
    void differentBodyProducesJsonDiffAndBytesDiff() {
        ParityRequestSnapshot rust = ParityRequestSnapshot.http(
                "POST", "https://example.com/x",
                Map.of("Content-Type", List.of("application/json")),
                "{\"k\":\"v\"}".getBytes());
        ParityRequestSnapshot java = ParityRequestSnapshot.http(
                "POST", "https://example.com/x",
                Map.of("Content-Type", List.of("application/json")),
                "{\"k\":\"X\"}".getBytes());

        ParityDiffRenderer.Result r = ParityDiffRenderer.render(rust, java);
        assertThat(r.identical()).isFalse();
        assertThat(r.summary()).contains("PARITY MISMATCH").contains("body");
        assertThat(r.bodyJsonDiff()).contains("\"op\" : \"replace\"")
                .contains("\"path\" : \"/k\"");
        assertThat(r.bodyBytesDiff()).contains("- 7b226b223a2276227d")
                .contains("+ 7b226b223a2258227d");
    }

    @Test
    void differentHeadersProduceHeadersDiff() {
        ParityRequestSnapshot rust = ParityRequestSnapshot.http(
                "POST", "https://example.com/x",
                Map.of("POLY_SIGNATURE", List.of("aaaa")),
                new byte[0]);
        ParityRequestSnapshot java = ParityRequestSnapshot.http(
                "POST", "https://example.com/x",
                Map.of("POLY_SIGNATURE", List.of("bbbb")),
                new byte[0]);
        ParityDiffRenderer.Result r = ParityDiffRenderer.render(rust, java);
        assertThat(r.identical()).isFalse();
        assertThat(r.headersDiff())
                .contains("- POLY_SIGNATURE: [aaaa]")
                .contains("+ POLY_SIGNATURE: [bbbb]");
    }

    @Test
    void missingHeaderOnOneSideShowsOnlyThatSide() {
        ParityRequestSnapshot rust = ParityRequestSnapshot.http(
                "GET", "https://example.com/x",
                Map.of("X-Custom", List.of("yes"),
                        "Content-Type", List.of("application/json")),
                new byte[0]);
        ParityRequestSnapshot java = ParityRequestSnapshot.http(
                "GET", "https://example.com/x",
                Map.of("Content-Type", List.of("application/json")),
                new byte[0]);

        ParityDiffRenderer.Result r = ParityDiffRenderer.render(rust, java);
        assertThat(r.identical()).isFalse();
        assertThat(r.headersDiff()).contains("- X-Custom: [yes]");
        assertThat(r.headersDiff()).doesNotContain("+ X-Custom");
    }

    @Test
    void wsSnapshotsCompareCorrectly() {
        ParityRequestSnapshot rust = ParityRequestSnapshot.ws(
                "market", "wss://example.com/ws/market",
                "{\"type\":\"market\",\"assets_ids\":[\"a\"]}");
        ParityRequestSnapshot java = ParityRequestSnapshot.ws(
                "market", "wss://example.com/ws/market",
                "{\"type\":\"market\",\"assets_ids\":[\"b\"]}");
        ParityDiffRenderer.Result r = ParityDiffRenderer.render(rust, java);
        assertThat(r.identical()).isFalse();
        assertThat(r.summary()).contains("PARITY MISMATCH");
        assertThat(r.bodyJsonDiff()).contains("\"path\" : \"/assets_ids/0\"");
    }
}
