package org.microprofile.nio.handler;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
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

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

public abstract class SocketProcesser implements Closeable {
    protected Selector selector;
    private SSLEngine sslEngine;
    protected ExecutorService pool;
    protected ProtocolHandler<?> protocolHandler;
    protected int maxPending;
    protected int pending;
    private ConcurrentLinkedQueue<ByteBuffer> recycleByteBufferQueue = new ConcurrentLinkedQueue<>();
    protected int blockSize;
    protected List<Thread> threads = new ArrayList<>();
    protected boolean ssl;
    protected boolean closed;
    private ByteBuffer netDataIn;
    private ByteBuffer netDataOut;

    protected static final int DEFAULT_BLOCK_SIZE = 2048;

    /**
     * @param pool
     * @param protocolHandler
     * @param maxPending
     * @throws IOException
     */
    public SocketProcesser(ExecutorService pool, ProtocolHandler<?> protocolHandler, int maxPending) throws IOException {
        this(pool, protocolHandler, null, maxPending, DEFAULT_BLOCK_SIZE);
    }

    /**
     * @param pool
     * @param protocolHandler
     * @param sslEngine
     * @param maxPending
     * @throws IOException
     */
    public SocketProcesser(ExecutorService pool, ProtocolHandler<?> protocolHandler, SSLEngine sslEngine, int maxPending)
            throws IOException {
        this(pool, protocolHandler, sslEngine, maxPending, DEFAULT_BLOCK_SIZE);
    }

    /**
     * @param pool
     * @param protocolHandler
     * @param sslEngine
     * @param maxPending
     * @param blockSize
     * @throws IOException
     */
    public SocketProcesser(ExecutorService pool, ProtocolHandler<?> protocolHandler, SSLEngine sslEngine, int maxPending,
            int blockSize) throws IOException {
        this.selector = Selector.open();
        if (null == pool) {
            pool = Executors.newFixedThreadPool(1);
        }
        this.pool = pool;
        this.protocolHandler = protocolHandler;
        this.sslEngine = sslEngine;
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
        ssl = null != sslEngine;
        if (ssl) {
            createBuffer();
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
                    if (key.isReadable()) {
                        ChannelContext<?> channelContext = (ChannelContext<?>) key.attachment();
                        SocketChannel socketChannel = channelContext.getSocketChannel();
                        ThreadHandler<?> threadHandler = channelContext.getThreadHandler();
                        if (null != channelContext) {
                            try {
                                if (ssl && sslEngine.getHandshakeStatus() == HandshakeStatus.NOT_HANDSHAKING) {
                                    netDataIn.clear();
                                    int n = socketChannel.read(netDataIn);
                                    netDataIn.flip();
                                    ByteBuffer byteBuffer = allocateAndWait();
                                    SSLEngineResult engineResult = sslEngine.unwrap(netDataIn, byteBuffer);
                                    doTask();
                                    while (0 < n && engineResult.getStatus() == SSLEngineResult.Status.OK) {
                                        byteBuffer.flip();
                                        if (threadHandler.addByteBuffer(byteBuffer)) {
                                            pool.execute(threadHandler);
                                        }
                                        netDataIn.clear();
                                        n = socketChannel.read(netDataIn);
                                        netDataIn.flip();
                                        byteBuffer = allocateAndWait();
                                        engineResult = sslEngine.unwrap(netDataIn, byteBuffer);
                                        doTask();
                                    }
                                    if (-1 == n) {
                                        channelContext.close();
                                    } else {
                                        recycleByteBufferQueue.add(byteBuffer);
                                    }
                                } else {
                                    ByteBuffer byteBuffer = allocateAndWait();
                                    int n = socketChannel.read(byteBuffer);
                                    while (0 < n) {
                                        byteBuffer.flip();
                                        if (threadHandler.addByteBuffer(byteBuffer)) {
                                            pool.execute(threadHandler);
                                        }
                                        byteBuffer = allocateAndWait();
                                        n = socketChannel.read(byteBuffer);
                                    }
                                    if (-1 == n) {
                                        channelContext.close();
                                    } else {
                                        recycleByteBufferQueue.add(byteBuffer);
                                    }
                                }
                            } catch (Exception ex) {
                                channelContext.close();
                            }
                        }
                    } else if (key.isAcceptable()) {
                        ServerSocketChannel server = (ServerSocketChannel) key.channel();
                        SocketChannel socketChannel = server.accept();
                        register(socketChannel.configureBlocking(false),
                                new ChannelContext<>(protocolHandler, this, socketChannel, ssl));
                        if (ssl) {
                            doHandShake(socketChannel);
                        }
                    }
                }
            }
        }
    }

    private void createBuffer() {
        SSLSession session = sslEngine.getSession();
        int packetBufferSize = session.getPacketBufferSize();
        netDataOut = ByteBuffer.allocate(packetBufferSize);
        netDataIn = ByteBuffer.allocate(packetBufferSize);
    }

    public void doHandShake(SocketChannel socketChannel) throws IOException {
        boolean notDone = true;
        sslEngine.beginHandshake();
        HandshakeStatus hsStatus = sslEngine.getHandshakeStatus();
        SSLSession session = sslEngine.getSession();
        ByteBuffer netData = ByteBuffer.allocate(session.getPacketBufferSize());
        ByteBuffer outData = ByteBuffer.wrap("Hello".getBytes());
        int applicationBufferSize = session.getApplicationBufferSize();
        blockSize = blockSize < applicationBufferSize ? applicationBufferSize : blockSize;
        ByteBuffer appDataIn = ByteBuffer.allocate(blockSize);
        netData.clear();
        while (notDone) {
            switch (hsStatus) {
            case FINISHED:
                break;
            case NEED_TASK:
                doTask();
                hsStatus = sslEngine.getHandshakeStatus();
                break;
            case NEED_UNWRAP:
                int count = socketChannel.read(netData);
                if (0 <= count) {
                    netData.flip();
                    SSLEngineResult result;
                    do {
                        appDataIn.clear();
                        result = sslEngine.unwrap(netData, appDataIn);
                        doTask();
                        hsStatus = sslEngine.getHandshakeStatus();
                    } while (result.getStatus() == SSLEngineResult.Status.OK
                            && hsStatus == SSLEngineResult.HandshakeStatus.NEED_UNWRAP);
                    if (netData.remaining() > 0) {
                        netData.compact();
                    } else {
                        netData.clear();
                    }
                }
                break;
            case NEED_WRAP:
                netDataOut.clear();
                sslEngine.wrap(outData, netDataOut);
                doTask();
                hsStatus = sslEngine.getHandshakeStatus();
                netDataOut.flip();
                socketChannel.write(netDataOut);
                break;
            case NOT_HANDSHAKING:
                notDone = false;
                break;
            }

        }

    }

    public ByteBuffer wrap(ByteBuffer src) throws SSLException {
        netDataOut.clear();
        sslEngine.wrap(src, netDataOut);
        doTask();
        netDataOut.flip();
        return netDataOut;
    }

    private void doTask() {
        Runnable task;
        while ((task = sslEngine.getDelegatedTask()) != null) {
            pool.execute(task);
        }
    }

    public ByteBuffer allocateAndWait() {
        while (0 < maxPending && maxPending < pending) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
            }
        }
        ByteBuffer byteBuffer = recycleByteBufferQueue.poll();
        if (null == byteBuffer) {
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
        if (selector.isOpen()) {
            selector.close();
        }
        closed = true;
        for (Thread thread : threads) {
            thread.interrupt();
        }
        if (null != pool && !pool.isShutdown()) {
            pool.shutdown();
        }
    }
}