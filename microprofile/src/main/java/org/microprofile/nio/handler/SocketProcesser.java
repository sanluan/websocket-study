package org.microprofile.nio.handler;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
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
    protected SocketChannel socketChannel;

    public SocketProcesser(ExecutorService pool, ProtocolHandler<?> protocolHandler, SocketChannel socketChannel)
            throws IOException {
        this.selector = Selector.open();
        this.pool = pool;
        this.protocolHandler = protocolHandler;
        this.socketChannel = socketChannel;
    }

    public void polling() throws IOException {
        if (0 < selector.select()) {
            Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();
            while (keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();
                keyIterator.remove();
                ChannelContext<?> channelContext;
                if (key.isAcceptable()) {
                    ServerSocketChannel server = (ServerSocketChannel) key.channel();
                    SocketChannel socketChannel = server.accept();
                    socketChannel.configureBlocking(false);
                    channelContext = new ChannelContext<>(key, protocolHandler, socketChannel);
                    socketChannel.register(key.selector(), SelectionKey.OP_READ, channelContext);
                } else if (key.isReadable()) {
                    SocketChannel client = (SocketChannel) key.channel();
                    ByteBuffer byteBuffer = ByteBuffer.allocateDirect(1024);
                    channelContext = (ChannelContext<?>) key.attachment();
                    if (null == channelContext) {
                        channelContext = new ChannelContext<>(key, protocolHandler, socketChannel);
                    }
                    int n = -1;
                    try {
                        n = client.read(byteBuffer);
                    } catch (Exception ex) {
                    }
                    if (n == -1) {
                        channelContext.close();
                    } else if (0 < n) {
                        ThreadHandler<?> threadHandler = channelContext.getThreadHandler();
                        threadHandler.addByteBuffer(byteBuffer);
                        if (!threadHandler.isRunning()) {
                            pool.execute(threadHandler);
                        }
                    }
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        selector.close();
        if (null != pool) {
            pool.shutdown();
        }
    }
}