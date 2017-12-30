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
    protected ProtocolHandler protocolHandler;

    public SocketProcesser(ExecutorService pool, ProtocolHandler protocolHandler) throws IOException {
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
                if (key.isAcceptable()) {
                    ServerSocketChannel server = (ServerSocketChannel) key.channel();
                    SocketChannel client = server.accept();
                    client.configureBlocking(false);
                    client.register(key.selector(), SelectionKey.OP_READ);
                } else if (key.isReadable()) {
                    SocketChannel client = (SocketChannel) key.channel();
                    ByteBuffer byteBuffer = ByteBuffer.allocate(1024);
                    int n = -1;
                    try {
                        n = client.read(byteBuffer);
                    } catch (Exception ex) {
                    }
                    if (n == -1) {
                        key.cancel();
                        client.close();
                    } else if (0 < n) {
                        pool.execute(new ThreadHandler(protocolHandler, byteBuffer, key));
                    }
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (selector.isOpen()) {
            selector.wakeup();
            Iterator<SelectionKey> keys = this.selector.keys().iterator();
            while (keys.hasNext()) {
                SelectionKey key = keys.next();
                key.cancel();
            }
            selector.close();
        }
        if (null != pool) {
            pool.shutdown();
        }
    }
}