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

public abstract class SocketProcesser implements Closeable {
    protected Selector selector;
    protected ExecutorService pool;
    protected ProtocolHandler<?> protocolHandler;
    protected int maxPending;
    protected int pending;
    private ConcurrentLinkedQueue<ByteBuffer> recycleByteBufferQueue = new ConcurrentLinkedQueue<>();
    protected int blockSize;
    protected List<Thread> threads = new ArrayList<>();;
    protected boolean closed;

    protected static final int DEFAULT_BLOCK_SIZE = 2048;

    /**
     * @param pool
     * @param protocolHandler
     * @param maxPending
     * @throws IOException
     */
    public SocketProcesser(ExecutorService pool, ProtocolHandler<?> protocolHandler, int maxPending) throws IOException {
        this(pool, protocolHandler, maxPending, DEFAULT_BLOCK_SIZE);
    }

    /**
     * @param pool
     * @param protocolHandler
     * @param maxPending
     * @param blockSize
     * @throws IOException
     */
    public SocketProcesser(ExecutorService pool, ProtocolHandler<?> protocolHandler, int maxPending, int blockSize)
            throws IOException {
        this.selector = Selector.open();
        if (null == pool) {
            pool = Executors.newFixedThreadPool(1);
        }
        this.pool = pool;
        this.protocolHandler = protocolHandler;
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
                            } catch (Exception ex) {
                                channelContext.close();
                            }
                        }
                    } else if (key.isAcceptable()) {
                        ServerSocketChannel server = (ServerSocketChannel) key.channel();
                        SocketChannel socketChannel = server.accept();
                        register(socketChannel.configureBlocking(false),
                                new ChannelContext<>(protocolHandler, this, socketChannel));
                    }
                }
            }
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
     * @param blockSize
     *            the blockSize to set
     */
    public void setBlockSize(int blockSize) {
        this.blockSize = blockSize;
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