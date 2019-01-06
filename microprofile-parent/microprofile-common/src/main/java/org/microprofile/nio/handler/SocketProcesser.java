package org.microprofile.nio.handler;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
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
                    SocketChannel socketChannel = (SocketChannel) key.channel();
                    ChannelContext<?> channelContext = (ChannelContext<?>) key.attachment();
                    try {
                        ByteBuffer byteBuffer = ByteBuffer.allocate(2048);
                        int n = socketChannel.read(byteBuffer);
                        if (0 < n) {
                            ThreadHandler<?> threadHandler = channelContext.getThreadHandler();
                            if (threadHandler.addByteBuffer(byteBuffer)) {
                                pool.execute(threadHandler);
                            }
                        } else if (-1 == n) {
                            channelContext.close();
                        }
                    } catch (Exception ex) {
                        channelContext.close();
                    }
                } else if (key.isAcceptable()) {
                    ServerSocketChannel server = (ServerSocketChannel) key.channel();
                    SocketChannel socketChannel = server.accept();
                    SelectableChannel selectableChannel = socketChannel.configureBlocking(false);
                    selectableChannel.register(key.selector(), SelectionKey.OP_READ,
                            new ChannelContext<>(protocolHandler, socketChannel));
                }
            }
        }
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