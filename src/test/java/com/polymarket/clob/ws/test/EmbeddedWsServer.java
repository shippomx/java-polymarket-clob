package com.polymarket.clob.ws.test;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * 集成测试用的嵌入式 WebSocket 服务器。基于 {@code org.java_websocket} 的
 * {@link WebSocketServer}，作用：
 *
 * <ul>
 *   <li>监听 {@code ws://127.0.0.1:port}，按 path（{@code /ws/market}、{@code /ws/user}）
 *       区分 channel；</li>
 *   <li>记录每条 incoming text frame 到队列中，供测试断言客户端发出的 subscribe / unsubscribe
 *       报文；</li>
 *   <li>对外开放 {@link #broadcast(String)} / {@link #pushTo(WebSocket, String)} 等接口，
 *       让测试主动推送 JSON 消息；</li>
 *   <li>{@link #closeAll(int)} 用于模拟服务端断连，测试客户端的重连 + 订阅重放。</li>
 * </ul>
 *
 * <p>使用方式：
 * <pre>{@code
 *   try (EmbeddedWsServer server = EmbeddedWsServer.start()) {
 *       URI base = server.baseUri(); // ws://127.0.0.1:<port>
 *       // ...构造 ClobWebSocketClient 指向 base
 *   }
 * }</pre>
 */
public final class EmbeddedWsServer extends WebSocketServer implements AutoCloseable {

    /** 每个 channel path 的所有收到的 text 帧（按到达顺序）。 */
    private final Map<String, LinkedBlockingDeque<String>> received = new ConcurrentHashMap<>();
    /** 每个 channel path 当前打开的连接数（用于断言"重连后又有新连接"）。 */
    private final Map<String, java.util.concurrent.atomic.AtomicInteger> openCount = new ConcurrentHashMap<>();
    private final CountDownLatch startLatch = new CountDownLatch(1);

    private EmbeddedWsServer(InetSocketAddress address) {
        super(address);
        setReuseAddr(true);
    }

    /**
     * 创建并启动一个监听 {@code 127.0.0.1:0}（系统分配端口）的嵌入式 server，
     * 调用方拿到的对象已经处于 ready 状态（{@code onStart} 已触发）。
     *
     * <p>命名故意避开 {@code start}：父类 {@code WebSocketServer.start()} 是实例
     * 方法，子类不能再以 {@code static start()} 重载（编译期会冲突）。</p>
     */
    public static EmbeddedWsServer launch() throws InterruptedException {
        EmbeddedWsServer server = new EmbeddedWsServer(new InetSocketAddress("127.0.0.1", 0));
        server.setDaemon(true);
        server.start();
        if (!server.startLatch.await(5, TimeUnit.SECONDS)) {
            try { server.stop(0); } catch (Exception ignored) { }
            throw new IllegalStateException("EmbeddedWsServer did not start within 5s");
        }
        return server;
    }

    public URI baseUri() {
        return URI.create("ws://127.0.0.1:" + getPort());
    }

    /** 等待指定 channel 收到至少一条消息（用于异步测试同步点）。 */
    public String awaitMessage(String channelPath, long timeoutMs) throws InterruptedException {
        LinkedBlockingDeque<String> q = received.computeIfAbsent(
                channelPath, k -> new LinkedBlockingDeque<>());
        String msg = q.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (msg == null) {
            throw new AssertionError("No message received on " + channelPath
                    + " within " + timeoutMs + "ms");
        }
        return msg;
    }

    /** 不阻塞地查看队列里目前还有多少待消费消息。 */
    public int pendingCount(String channelPath) {
        return received.computeIfAbsent(channelPath, k -> new LinkedBlockingDeque<>()).size();
    }

    /** 当前持有该 channel 的活跃连接数。 */
    public int openConnections(String channelPath) {
        return openCount.computeIfAbsent(
                channelPath, k -> new java.util.concurrent.atomic.AtomicInteger()).get();
    }

    /** 把 JSON 推给所有连到给定 channel 的客户端。 */
    public void broadcast(String channelPath, String json) {
        for (WebSocket conn : getConnections()) {
            if (channelPath.equals(conn.getResourceDescriptor())) {
                conn.send(json);
            }
        }
    }

    public void pushTo(WebSocket conn, String json) {
        Objects.requireNonNull(conn, "conn").send(json);
    }

    /** 主动断开所有客户端，用于触发客户端重连流程。 */
    public void closeAll(int code) {
        for (WebSocket conn : getConnections()) {
            conn.close(code, "test-server-disconnect");
        }
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String path = conn.getResourceDescriptor();
        openCount.computeIfAbsent(path, k -> new java.util.concurrent.atomic.AtomicInteger())
                .incrementAndGet();
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String path = conn.getResourceDescriptor();
        java.util.concurrent.atomic.AtomicInteger c = openCount.get(path);
        if (c != null) c.decrementAndGet();
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        String path = conn.getResourceDescriptor();
        received.computeIfAbsent(path, k -> new LinkedBlockingDeque<>()).add(message);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        // 测试期间不让 server 退出，把异常吞掉但保留可观测性。
        // SLF4J 不强引入；用 stderr 简单输出。
        System.err.println("[EmbeddedWsServer] onError: " + ex);
    }

    @Override
    public void onStart() {
        startLatch.countDown();
    }

    @Override
    public void close() {
        try {
            stop(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
