package org.microprofile.nio.handler;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public abstract class SocketProcesser implements Closeable {
    protected Selector selector;
    protected ExecutorService pool;
    protected ProtocolHandler<?> protocolHandler;
    protected int maxPending;

    public SocketProcesser(ExecutorService pool, ProtocolHandler<?> protocolHandler, int maxPending) throws IOException {
        this.selector = Selector.open();
        if (null == pool) {
            pool = Executors.newFixedThreadPool(1);
        }
        this.pool = pool;
        this.protocolHandler = protocolHandler;
        this.maxPending = maxPending;
    }

    public void polling() throws IOException {
        if (0 < selector.select()) {
            Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();
            while (keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();
                keyIterator.remove();
                if (key.isReadable()) {
                    ChannelContext<?> channelContext = (ChannelContext<?>) key.attachment();
                    if (null != channelContext) {
                        try {
                            ByteBuffer byteBuffer = ThreadHandler.getByteBuffer();
                            int n = channelContext.read(byteBuffer, maxPending);
                            while (0 < n) {
                                byteBuffer.flip();
                                if (channelContext.getThreadHandler().addByteBuffer(byteBuffer)) {
                                    pool.execute(channelContext.getThreadHandler());
                                }
                                byteBuffer = ThreadHandler.getByteBuffer();
                                n = channelContext.read(byteBuffer, maxPending);
                            }
                            if (-1 == n) {
                                channelContext.close();
                            } else {
                                ThreadHandler.recycle(byteBuffer);
                            }
                        } catch (Exception ex) {
                            channelContext.close();
                        }
                    }
                }
            }
        }
    }

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