package org.microprofile.file.client;

import java.io.Closeable;
import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.microprofile.file.handler.RemoteEventHandler;
import org.microprofile.websocket.WebSocketClient;

/**
 * 带"服务器端掉线自动重连"的同步客户端。
 * <p>
 * 用法：
 *
 * <pre>
 * FileListener listener = new FileListener(&quot;D:/clientDir&quot;);
 * FileEventHandler handler = new FileEventHandler(listener.getLocalFileAdaptor(), SyncMode.BIDIRECTIONAL);
 * FileSyncClient client = new FileSyncClient(&quot;localhost&quot;, 20001, &quot;/&quot;, handler);
 * client.start();                       // 起一个后台线程负责连接与重连
 * listener.addEventHandler(handler);
 * listener.start();
 * ...
 * client.close();                       // 退出时关闭（之后不再重连）
 * </pre>
 * </p>
 * <p>
 * 判定"掉线"以协议层有没有可用会话为准（{@code RemoteMessageHandler.hasSession()}）：
 * 服务器进程退出、网络断开、握手未完成都会走到重连分支。重连成功后协议层的
 * {@code onOpen} 会重新触发一次对账，因此掉线期间本地新增/修改的文件会在重连后补齐。
 * </p>
 * <p>
 * 重连间隔按指数退避：{@link #getInitialRetryMillis()} 起，每次失败翻倍，封顶
 * {@link #getMaxRetryMillis()}；连上后计数清零。{@link #getMaxAttempts()} 为 0 表示一直重试。
 * </p>
 */
public class FileSyncClient implements Closeable {

    protected final Log log = LogFactory.getLog(getClass());

    private final String host;
    private final int port;
    private final String path;
    private final RemoteEventHandler remoteEventHandler;

    /**
     * 断线后第一次重连的等待时间（毫秒）
     */
    private volatile long initialRetryMillis = 1000L;
    /**
     * 重连等待上限（毫秒）
     */
    private volatile long maxRetryMillis = 30000L;
    /**
     * 连接正常时多久检查一次会话（毫秒）
     */
    private volatile long healthCheckMillis = 1000L;
    /**
     * 连上之后多久还没完成握手，就把这次连接判为失败（毫秒）
     */
    private volatile long handshakeTimeoutMillis = 3000L;
    /**
     * 最多尝试连接几次，0 表示一直重试
     */
    private volatile int maxAttempts = 0;

    private volatile WebSocketClient client;
    private volatile boolean closed;
    private volatile boolean connected;
    private volatile boolean everConnected;
    /**
     * 成功重连次数（首次连接不计）
     */
    private volatile long reconnectCount;
    /**
     * 累计连接尝试次数（含首次与失败）
     */
    private volatile long attemptCount;
    private volatile String lastError;
    private Thread worker;

    /**
     * @param host
     *            服务器地址
     * @param port
     *            服务器端口
     * @param path
     *            握手路径
     * @param remoteEventHandler
     *            本地的事件处理器（{@link org.microprofile.file.handler.FileEventHandler} 或
     *            {@link org.microprofile.file.handler.ArchiveEventHandler}）。
     *            每次连接都会向它要一个"客户端"身份（{@code getRemoteMessageHandler(false)}），
     *            所以断开重连复用同一个协议处理器，会话与对账状态是连续的。
     */
    public FileSyncClient(String host, int port, String path, RemoteEventHandler remoteEventHandler) {
        super();
        if (null == host || 0 >= port) {
            throw new IllegalArgumentException("host/port is invalid");
        }
        if (null == remoteEventHandler) {
            throw new IllegalArgumentException("remoteEventHandler can't be null");
        }
        this.host = host;
        this.port = port;
        this.path = null == path ? "/" : path;
        this.remoteEventHandler = remoteEventHandler;
    }

    /**
     * 启动连接线程：先在后台连一次，失败按退避重试；连上之后持续监控，掉线自动重连。
     * 线程是 daemon，不会阻止 JVM 退出。
     *
     * @return this
     */
    public synchronized FileSyncClient start() {
        if (null != worker) {
            return this;
        }
        closed = false;
        worker = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "filesync-client-" + host + ":" + port);
        worker.setDaemon(true);
        worker.start();
        return this;
    }

    /**
     * 连接与重连主循环：没有会话就去连，连上之后定时体检，掉线回到连接分支。
     */
    private void loop() {
        int failures = 0;
        while (!closed) {
            if (hasSession()) {
                connected = true;
                sleep(healthCheckMillis);
                continue;
            }
            if (connected) {
                // 会话没了：服务器掉线（进程退出、网络断开、服务端主动关闭）
                connected = false;
                log.warn("与服务器 " + host + ":" + port + " 的连接已断开，开始重连");
            }
            closeQuietly(client);
            client = null;
            attemptCount++;
            if (0 < maxAttempts && attemptCount > maxAttempts) {
                lastError = "重试次数已达上限 " + maxAttempts;
                log.error("放弃重连：" + lastError);
                return;
            }
            try {
                connectOnce();
                failures = 0;
                if (everConnected) {
                    reconnectCount++;
                    log.info("已重新连上服务器 " + host + ":" + port + "（第 " + attemptCount + " 次尝试，累计重连 "
                            + reconnectCount + " 次）");
                } else {
                    log.info("已连上服务器 " + host + ":" + port);
                }
                everConnected = true;
                connected = true;
            } catch (Exception e) {
                failures++;
                lastError = e.toString();
                long delay = retryDelay(failures);
                log.warn("连接服务器 " + host + ":" + port + " 失败（第 " + attemptCount + " 次）：" + e + "，" + delay + "ms 后重试");
                closeQuietly(client);
                client = null;
                sleep(delay);
            }
        }
    }

    /**
     * 连一次，并等到协议层真正建立会话；建不起来就当失败抛异常。
     *
     * @throws Exception
     */
    private void connectOnce() throws Exception {
        WebSocketClient newClient = new WebSocketClient(host, port, path,
                remoteEventHandler.getRemoteMessageHandler(false));
        client = newClient;
        newClient.asyncListen();
        long deadline = System.currentTimeMillis() + handshakeTimeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (hasSession()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IOException("连接已建立但 " + handshakeTimeoutMillis + "ms 内没有完成会话握手");
    }

    /**
     * @param failures
     *            连续失败次数（从 1 开始）
     * @return 本次重试前等待的毫秒数（指数退避，封顶 maxRetryMillis）
     */
    private long retryDelay(int failures) {
        long delay = initialRetryMillis;
        for (int i = 1; i < failures && maxRetryMillis > delay; i++) {
            delay <<= 1;
        }
        return Math.min(delay, Math.max(initialRetryMillis, maxRetryMillis));
    }

    private boolean hasSession() {
        try {
            WebSocketClient current = client;
            if (null == current) {
                return false;
            }
            return remoteEventHandler.getRemoteMessageHandler(false).hasSession();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // close() 会打断等待，这里直接结束
            Thread.currentThread().interrupt();
        }
    }

    private void closeQuietly(WebSocketClient target) {
        if (null == target) {
            return;
        }
        try {
            target.close();
        } catch (IOException e) {
            log.debug("关闭旧连接出错", e);
        }
    }

    /**
     * 停止重连并关闭当前连接（之后不会再自动重连）。可重复调用。
     */
    @Override
    public synchronized void close() {
        closed = true;
        connected = false;
        Thread current = worker;
        worker = null;
        if (null != current) {
            current.interrupt();
        }
        closeQuietly(client);
        client = null;
    }

    /**
     * @return 当前是否连上（协议层有可用会话）
     */
    public boolean isConnected() {
        return connected && hasSession();
    }

    /**
     * @return 成功重连的次数（首次连接不算）
     */
    public long getReconnectCount() {
        return reconnectCount;
    }

    /**
     * @return 累计连接尝试次数（含首次与失败次数）
     */
    public long getAttemptCount() {
        return attemptCount;
    }

    /**
     * @return 最近一次连接失败的错误信息（没失败过则为 null）
     */
    public String getLastError() {
        return lastError;
    }

    /**
     * @return 当前的 WebSocket 客户端（未连接时为 null）
     */
    public WebSocketClient getClient() {
        return client;
    }

    /**
     * @return 服务器地址
     */
    public String getHost() {
        return host;
    }

    /**
     * @return 服务器端口
     */
    public int getPort() {
        return port;
    }

    /**
     * @return 断线后第一次重连的等待时间（毫秒）
     */
    public long getInitialRetryMillis() {
        return initialRetryMillis;
    }

    /**
     * @param initialRetryMillis
     *            断线后第一次重连的等待时间（毫秒）
     */
    public void setInitialRetryMillis(long initialRetryMillis) {
        if (0 >= initialRetryMillis) {
            throw new IllegalArgumentException("initialRetryMillis must be positive");
        }
        this.initialRetryMillis = initialRetryMillis;
    }

    /**
     * @return 重连等待上限（毫秒）
     */
    public long getMaxRetryMillis() {
        return maxRetryMillis;
    }

    /**
     * @param maxRetryMillis
     *            重连等待上限（毫秒）
     */
    public void setMaxRetryMillis(long maxRetryMillis) {
        if (0 >= maxRetryMillis) {
            throw new IllegalArgumentException("maxRetryMillis must be positive");
        }
        this.maxRetryMillis = maxRetryMillis;
    }

    /**
     * @return 连接正常时的会话检查间隔（毫秒）
     */
    public long getHealthCheckMillis() {
        return healthCheckMillis;
    }

    /**
     * @param healthCheckMillis
     *            连接正常时的会话检查间隔（毫秒），越小越早发现掉线、也越费一点 CPU
     */
    public void setHealthCheckMillis(long healthCheckMillis) {
        if (0 >= healthCheckMillis) {
            throw new IllegalArgumentException("healthCheckMillis must be positive");
        }
        this.healthCheckMillis = healthCheckMillis;
    }

    /**
     * @return 握手超时（毫秒）
     */
    public long getHandshakeTimeoutMillis() {
        return handshakeTimeoutMillis;
    }

    /**
     * @param handshakeTimeoutMillis
     *            握手超时（毫秒）：连接建立后这么久还没有会话，当作这次连接失败
     */
    public void setHandshakeTimeoutMillis(long handshakeTimeoutMillis) {
        if (0 >= handshakeTimeoutMillis) {
            throw new IllegalArgumentException("handshakeTimeoutMillis must be positive");
        }
        this.handshakeTimeoutMillis = handshakeTimeoutMillis;
    }

    /**
     * @return 最多尝试次数，0 表示一直重试
     */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * @param maxAttempts
     *            最多尝试次数，0 表示一直重试
     */
    public void setMaxAttempts(int maxAttempts) {
        if (0 > maxAttempts) {
            throw new IllegalArgumentException("maxAttempts can't be negative");
        }
        this.maxAttempts = maxAttempts;
    }

    @Override
    public String toString() {
        return "FileSyncClient [" + host + ":" + port + " connected=" + connected + ", attempts=" + attemptCount
                + ", reconnects=" + reconnectCount + (null == lastError ? "" : ", lastError=" + lastError) + "]";
    }
}
