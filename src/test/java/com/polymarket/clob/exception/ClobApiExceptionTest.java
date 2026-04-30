package com.polymarket.clob.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClobApiExceptionTest {

    @Test
    void carriesStatusMethodPathBody() {
        ClobApiException ex = new ClobApiException(404, "GET", "/markets/foo", "not found");
        assertThat(ex.getStatusCode()).isEqualTo(404);
        assertThat(ex.getMethod()).isEqualTo("GET");
        assertThat(ex.getPath()).isEqualTo("/markets/foo");
        assertThat(ex.getBody()).isEqualTo("not found");
        assertThat(ex.getMessage()).contains("404").contains("GET").contains("/markets/foo");
    }

    @Test
    void truncatesLargeBodyKeepingHeadAndTail() {
        // 构造首尾各有标志字符的大 body，验证首尾双向保留
        String head = "H".repeat(ClobApiException.HEAD_CHARS);
        String middle = "M".repeat(20_000);
        String tail = "T".repeat(ClobApiException.TAIL_CHARS);
        String bigBody = head + middle + tail;

        ClobApiException ex = new ClobApiException(500, "POST", "/orders", bigBody);

        String body = ex.getBody();
        // 结果 = HEAD + marker + TAIL；长度略大于 MAX_BODY_CHARS（marker 本身占位）
        assertThat(body).startsWith(head);
        assertThat(body).endsWith(tail);
        assertThat(body).contains("[truncated 20000 chars]");
        // 原始长度 - (HEAD + TAIL) == 20000，校验统计数字
        assertThat(body).doesNotContain("M".repeat(21_000));
    }

    @Test
    void nullBodyBecomesEmpty() {
        ClobApiException ex = new ClobApiException(500, "GET", "/x", null);
        assertThat(ex.getBody()).isEmpty();
    }

    @Test
    void bodyExactlyAtLimitIsNotTruncated() {
        String atLimit = "y".repeat(ClobApiException.MAX_BODY_CHARS);
        ClobApiException ex = new ClobApiException(502, "GET", "/x", atLimit);
        assertThat(ex.getBody()).isEqualTo(atLimit);
        assertThat(ex.getBody()).hasSize(ClobApiException.MAX_BODY_CHARS);
        assertThat(ex.getBody()).doesNotContain("truncated");
    }

    @Test
    void bodyOneCharOverLimitIsTruncated() {
        // 边界：MAX+1 触发截断；omitted = 1
        String body = "a".repeat(ClobApiException.MAX_BODY_CHARS + 1);
        ClobApiException ex = new ClobApiException(502, "GET", "/x", body);
        assertThat(ex.getBody()).contains("[truncated 1 chars]");
    }

    @Test
    void headAndTailSumEqualsMax() {
        // 确保常量关系稳定，后续调参时不会出现 HEAD+TAIL > MAX 导致 substring 越界
        assertThat(ClobApiException.HEAD_CHARS + ClobApiException.TAIL_CHARS)
                .isEqualTo(ClobApiException.MAX_BODY_CHARS);
    }

    @Test
    void isClobException() {
        assertThat(new ClobApiException(500, "GET", "/x", "err"))
                .isInstanceOf(ClobException.class)
                .isInstanceOf(RuntimeException.class);
    }
}
