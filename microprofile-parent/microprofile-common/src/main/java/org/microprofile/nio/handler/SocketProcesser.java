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

public abstract class SocketProcesser implements Closeable {
    protected Selector selector;
    protected ExecutorService pool;
    protected ProtocolHandler<?> protocolHandler;

    public SocketProcesser(ExecutorService pool, ProtocolHandler<?> protocolHandler) throws IOException {
        this.selector = Selector.open();
        this.pool = pool;
        this.protocolHandler = protocolHandler;
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
                            ThreadHandler<?> threadHandler = channelContext.getThreadHandler();
                            ByteBuffer byteBuffer = threadHandler.getByteBuffer();
                            int n = channelContext.read(byteBuffer);
                            while (0 < n) {
                                byteBuffer.flip();
                                if (threadHandler.addByteBuffer(byteBuffer)) {
                                    pool.execute(threadHandler);
                                }
                                byteBuffer = threadHandler.getByteBuffer();
                                n = channelContext.read(byteBuffer);
                            }
                            if (-1 == n) {
                                channelContext.close();
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