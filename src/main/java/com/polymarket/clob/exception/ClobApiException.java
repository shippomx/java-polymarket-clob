package com.polymarket.clob.exception;

import lombok.Getter;

/**
 * 上游 CLOB REST API 返回非 2xx 状态码时抛出。
 *
 * <p>保留 HTTP 状态码、请求方法、请求路径以及响应正文供调用方诊断。
 * 为防止日志膨胀，{@code body} 若超过 {@link #MAX_BODY_CHARS} 会做"首 {@link #HEAD_CHARS} + 尾 {@link #TAIL_CHARS}"截断，
 * 中间插入 {@code "…[truncated N chars]…"} 标记。首尾双向保留是因为上游的错误 dump
 * 经常把关键信息（错误码 / 堆栈尾 / contract 地址）放在响应末尾，纯"前缀截断"会把它们切掉。</p>
 */
@Getter
public class ClobApiException extends ClobException {

    /** 响应正文保留的最大字符数（截断阈值，超过则启动首尾双向截断）。 */
    public static final int MAX_BODY_CHARS = 16 * 1024;

    /** 截断时首部保留的字符数。 */
    public static final int HEAD_CHARS = 10 * 1024;

    /** 截断时尾部保留的字符数。 */
    public static final int TAIL_CHARS = MAX_BODY_CHARS - HEAD_CHARS;

    private final int statusCode;
    private final String method;
    private final String path;
    private final String body;

    public ClobApiException(int statusCode, String method, String path, String body) {
        super(String.format("CLOB API %d %s %s", statusCode, method, path));
        this.statusCode = statusCode;
        this.method = method;
        this.path = path;
        this.body = truncate(body);
    }

    private static String truncate(String body) {
        if (body == null) return "";
        int len = body.length();
        if (len <= MAX_BODY_CHARS) return body;
        int omitted = len - HEAD_CHARS - TAIL_CHARS;
        return body.substring(0, HEAD_CHARS)
                + "…[truncated " + omitted + " chars]…"
                + body.substring(len - TAIL_CHARS);
    }
}
