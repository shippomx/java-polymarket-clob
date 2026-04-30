package com.polymarket.clob.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * 游标分页流。对齐 Rust {@code stream_data} 与 {@code Page<T>}：
 * <ul>
 *   <li>首次调用不附 {@code next_cursor}</li>
 *   <li>后续每次调用带上一页返回的 {@code next_cursor}</li>
 *   <li>服务端返回 {@link #TERMINAL_CURSOR}（base64 编码的 "-1"）时终止</li>
 * </ul>
 *
 * <p>实现为惰性 {@link Stream}：只有在消费到下一页时才触发一次 HTTP 调用。
 * 当前实现以阻塞方式（{@code CompletableFuture#join()}）等待每一页，适用于同步聚合场景；
 * 如需完全异步，Plan 2+ 可补 {@code Flow<T>} / 反应式 API。</p>
 */
public final class CursorPager {

    private static final Logger log = LoggerFactory.getLogger(CursorPager.class);

    /** Polymarket 终止游标：base64("-1") == "LTE=". */
    public static final String TERMINAL_CURSOR = "LTE=";

    private CursorPager() {}

    @Value
    @Builder
    @Jacksonized
    public static class Page<T> {
        @JsonProperty("data") List<T> data;
        @JsonProperty("next_cursor") String nextCursor;
    }

    public static <T> Stream<T> stream(HttpTransport transport,
                                       String path,
                                       Map<String, ?> baseParams,
                                       TypeReference<Page<T>> pageType) {
        return stream(transport, path, baseParams, Map.of(), pageType);
    }

    /**
     * 带静态请求头的游标分页。给需要 L2 认证的端点用：
     * 同一次 {@code get_orders / get_trades} 调用所有分页共用一组头（包括 timestamp 与签名）。
     * py-clob-client 行为一致。
     */
    public static <T> Stream<T> stream(HttpTransport transport,
                                       String path,
                                       Map<String, ?> baseParams,
                                       Map<String, String> headers,
                                       TypeReference<Page<T>> pageType) {
        Map<String, String> hdrs = headers == null ? Map.of() : Map.copyOf(headers);
        Iterator<T> it = new Iterator<>() {
            private Iterator<T> current = List.<T>of().iterator();
            private String nextCursor = null;
            private boolean initialFetched = false;
            private boolean terminated = false;

            @Override
            public boolean hasNext() {
                while (!current.hasNext()) {
                    if (terminated) return false;
                    fetchNextPage();
                }
                return true;
            }

            @Override
            public T next() {
                if (!hasNext()) throw new NoSuchElementException();
                return current.next();
            }

            private void fetchNextPage() {
                Map<String, Object> params = new HashMap<>(baseParams);
                if (initialFetched) {
                    if (nextCursor == null || TERMINAL_CURSOR.equals(nextCursor)) {
                        terminated = true;
                        return;
                    }
                    params.put("next_cursor", nextCursor);
                }
                initialFetched = true;
                if (log.isDebugEnabled()) {
                    log.debug("CursorPager fetch path={} cursor={}", path, nextCursor);
                }
                Page<T> page = transport.get(path, params, hdrs, pageType).join();
                List<T> data = page.getData() == null ? List.of() : page.getData();
                current = data.iterator();
                nextCursor = page.getNextCursor();
                if (log.isDebugEnabled()) {
                    log.debug("CursorPager page path={} got={} items nextCursor={}",
                            path, data.size(), nextCursor);
                }
                if (TERMINAL_CURSOR.equals(nextCursor) && !current.hasNext()) {
                    terminated = true;
                }
            }
        };
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED), false);
    }
}
