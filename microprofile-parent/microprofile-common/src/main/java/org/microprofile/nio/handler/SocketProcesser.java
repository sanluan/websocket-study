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
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public abstract class SocketProcesser implements Closeable {
    protected Selector selector;
    protected ExecutorService pool;
    protected ProtocolHandler<?> protocolHandler;
    protected int maxPending;
    protected int pending;
    protected int blockSize;

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
    }

    /**
     * @throws IOException
     */
    public void polling() throws IOException {
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
                            }
                        } catch (Exception ex) {
                            channelContext.close();
                        }
                    }
                } else if (key.isAcceptable()) {
                    ServerSocketChannel server = (ServerSocketChannel) key.channel();
                    SocketChannel socketChannel = server.accept();
                    register(socketChannel.configureBlocking(false), new ChannelContext<>(protocolHandler, this, socketChannel));
                }
            }
        }
    }

    public ByteBuffer allocateAndWait() {
        while (0 < maxPending && maxPending < pending) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
            }
        }
        return ByteBuffer.allocateDirect(blockSize);
    }

    /**
     * @param selectableChannel
     * @param channelContext
     * @throws ClosedChannelException
     */
    public void register(SelectableChannel selectableChannel, ChannelContext<?> channelContext) throws ClosedChannelException {
        selectableChannel.register(selector, SelectionKey.OP_READ, channelContext);
    }

    /**
     * @param maxPending
     *            the maxPending to set
     */
    public void setMaxPending(int maxPending) {
        this.maxPending = maxPending;
    }

    /**
     * 
     */
    public void add() {
        pending++;
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

    @Override
    public void close() throws IOException {
        if (selector.isOpen()) {
            selector.close();
        }
        if (null != pool && !pool.isShutdown()) {
            pool.shutdown();
        }
    }
}