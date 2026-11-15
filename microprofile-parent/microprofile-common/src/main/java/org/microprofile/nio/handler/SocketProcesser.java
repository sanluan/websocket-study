package org.microprofile.nio.handler;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public abstract class SocketProcesser implements Closeable {
    protected final Log log = LogFactory.getLog(getClass());
    protected Selector selector;
    protected ExecutorService pool;
    protected ProtocolHandler<?> protocolHandler;
    protected SSLContext sslContext;
    protected boolean needClientAuth;
    protected int maxPending;
    protected int pending;
    private ConcurrentLinkedQueue<ByteBuffer> recycleByteBufferQueue = new ConcurrentLinkedQueue<>();
    protected int blockSize;
    protected List<Thread> threads = new ArrayList<>();
    protected volatile boolean closed;

    protected static final int DEFAULT_BLOCK_SIZE = 2048;

    /**
     * @param pool
     * @param protocolHandler
     * @param maxPending
     * @throws IOException
     */
    public SocketProcesser(ExecutorService pool, ProtocolHandler<?> protocolHandler, int maxPending) throws IOException {
        this(pool, protocolHandler, null, maxPending, DEFAULT_BLOCK_SIZE, false);
    }

    /**
     * @param pool
     * @param protocolHandler
     * @param sslContext
     * @param maxPending
     * @throws IOException
     */
    public SocketProcesser(ExecutorService pool, ProtocolHandler<?> protocolHandler, SSLContext sslContext, int maxPending)
            throws IOException {
        this(pool, protocolHandler, sslContext, maxPending, DEFAULT_BLOCK_SIZE);
    }

    /**
     * @param pool
     * @param protocolHandler
     * @param sslContext
     * @param maxPending
     * @param blockSize
     * @throws IOException
     */
    public SocketProcesser(ExecutorService pool, ProtocolHandler<?> protocolHandler, SSLContext sslContext, int maxPending,
            int blockSize) throws IOException {
        this(pool, protocolHandler, sslContext, maxPending, blockSize, false);
    }

    /**
     * @param pool
     * @param protocolHandler
     * @param sslContext
     * @param maxPending
     * @param blockSize
     * @param needClientAuth
     * @throws IOException
     */
    public SocketProcesser(ExecutorService pool, ProtocolHandler<?> protocolHandler, SSLContext sslContext, int maxPending,
            int blockSize, boolean needClientAuth) throws IOException {
        this.selector = Selector.open();
        if (null == pool) {
            pool = Executors.newFixedThreadPool(1);
        }
        this.pool = pool;
        this.protocolHandler = protocolHandler;
        this.sslContext = sslContext;
        this.needClientAuth = needClientAuth;
        this.maxPending = maxPending;
        this.blockSize = blockSize;
        if (0 < maxPending) {
            StringBuilder sb = new StringBuilder("Thread [Selector ");
            sb.append(selector.hashCode()).append(" cleaner]");
            Thread clearThread = new Thread(sb.toString()) {
                public void run() {
                    while (!closed) {
                        try {
                            Thread.sleep(1 * 60 * 1000);
                        } catch (InterruptedException e) {
                        }
                        clear();
                    }
                }
            };
            clearThread.start();
            threads.add(clearThread);
        }
    }

    /**
     * @throws IOException
     */
    public void asyncListen() throws IOException {
        Thread listenerThread = new Thread(getName()) {
            public void run() {
                try {
                    if (!closed) {
                        listen();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        };
        listenerThread.start();
        threads.add(listenerThread);
    }

    /**
     * @throws IOException
     */
    public void listen() throws IOException {
        while (isOpen()) {
            if (0 < selector.select()) {
                Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();
                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    keyIterator.remove();
                    try {
                        if (key.isReadable()) {
                            Object attachment = key.attachment();
                            if (attachment instanceof ChannelContext) {
                                read((ChannelContext<?>) attachment);
                            }
                        } else if (key.isAcceptable()) {
                            ServerSocketChannel server = (ServerSocketChannel) key.channel();
                            SocketChannel socketChannel = server.accept();
                            if (null != socketChannel) {
                                socketChannel.configureBlocking(false);
                                ChannelContext<?> channelContext = new ChannelContext<>(protocolHandler, this, socketChannel,
                                        createSSLEngine(), blockSize);
                                register(socketChannel, channelContext);
                            }
                        }
                    } catch (CancelledKeyException e) {
                    } catch (Exception e) {
                        // 单个连接出错不能拖垮 selector 线程
                        log.error("handle selection key error", e);
                    }
                }
            }
        }
    }

    /**
     * @param channelContext
     * @throws IOException
     */
    protected void read(ChannelContext<?> channelContext) throws IOException {
        ThreadHandler<?> threadHandler = channelContext.getThreadHandler();
        ByteBuffer byteBuffer = null;
        try {
            byteBuffer = allocateAndWait(channelContext.getBlockSize());
            int n = channelContext.read(byteBuffer);
            while (0 < n) {
                byteBuffer.flip();
                if (threadHandler.addByteBuffer(byteBuffer)) {
                    pool.execute(threadHandler);
                }
                byteBuffer = allocateAndWait(channelContext.getBlockSize());
                n = channelContext.read(byteBuffer);
            }
            if (-1 == n) {
                channelContext.close();
            }
        } catch (Exception ex) {
            channelContext.close();
        } finally {
            if (null != byteBuffer) {
                recycleByteBufferQueue.add(byteBuffer);
            }
        }
    }

    /**
     * 服务端使用的 SSL 引擎，sslContext 为 null 时返回 null（即明文通信）
     *
     * @return
     */
    protected SSLEngine createSSLEngine() {
        if (null == sslContext) {
            return null;
        }
        SSLEngine sslEngine = sslContext.createSSLEngine();
        sslEngine.setUseClientMode(false);
        sslEngine.setNeedClientAuth(needClientAuth);
        return sslEngine;
    }

    public void execute(Runnable task) {
        pool.execute(task);
    }

    public ByteBuffer allocateAndWait(int blockSize) {
        while (0 < maxPending && maxPending < pending) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
            }
        }
        ByteBuffer byteBuffer = recycleByteBufferQueue.poll();
        if (null == byteBuffer || byteBuffer.capacity() < blockSize) {
            byteBuffer = ByteBuffer.allocateDirect(blockSize);
        } else {
            byteBuffer.clear();
        }
        return byteBuffer;
    }

    public abstract boolean isOpen() throws IOException;

    public abstract String getName() throws IOException;

    /**
     * @param selectableChannel
     * @param channelContext
     * @throws ClosedChannelException
     */
    public void register(SelectableChannel selectableChannel, ChannelContext<?> channelContext) throws ClosedChannelException {
        selectableChannel.register(selector, SelectionKey.OP_READ, channelContext);
    }

    /**
     * 
     */
    public void add() {
        pending++;
    }

    public void clear() {
        int threshold = maxPending / 10;
        while (0 >= pending && threshold < recycleByteBufferQueue.size()) {
            recycleByteBufferQueue.poll();
        }
    }

    /**
     * @return the recycleByteBufferQueue
     */
    public ConcurrentLinkedQueue<ByteBuffer> getRecycleByteBufferQueue() {
        return recycleByteBufferQueue;
    }

    /**
     * 
     */
    public void minus() {
        pending--;
    }

    public ProtocolHandler<?> getProtocolHandler() {
        return protocolHandler;
    }

    @Override
    public void close() throws IOException {
        closed = true;
        // 先把已建立的连接全部关掉：只关 selector 的话，对端收不到 EOF，
        // 会一直以为连接还在（服务端重启/退出时客户端也就感知不到掉线）
        closeChannels();
        if (selector.isOpen()) {
            selector.close();
        }
        for (Thread thread : threads) {
            thread.interrupt();
        }
        if (null != pool && !pool.isShutdown()) {
            pool.shutdown();
        }
    }

    /**
     * 关闭 selector 上注册的全部通道（含监听通道），单个通道出错不影响其它通道
     */
    private void closeChannels() {
        SelectionKey[] keys;
        try {
            keys = selector.keys().toArray(new SelectionKey[0]);
        } catch (ClosedSelectorException e) {
            return;
        }
        for (SelectionKey key : keys) {
            try {
                key.channel().close();
            } catch (Exception e) {
                log.debug("关闭通道出错", e);
            }
            try {
                key.cancel();
            } catch (Exception e) {
                log.debug("取消 selection key 出错", e);
            }
        }
    }
}
