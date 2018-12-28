package org.microprofile.nio;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.microprofile.nio.handler.ProtocolHandler;
import org.microprofile.nio.handler.SocketProcesser;

public class SocketClient extends SocketProcesser implements Closeable {
    SocketChannel socketChannel;

    public SocketClient(String host, int port, ExecutorService pool, ProtocolHandler<?> protocolHandler) throws IOException {
        this(new InetSocketAddress(host, port), pool, protocolHandler);
    }

    public SocketClient(SocketAddress socketAddress, ExecutorService pool, ProtocolHandler<?> protocolHandler)
            throws IOException {
        super(pool, protocolHandler);
        socketChannel = SocketChannel.open(socketAddress);
        socketChannel.configureBlocking(false);
        socketChannel.register(selector, SelectionKey.OP_READ);
    }

    public void listen() throws IOException {
        if (null == pool) {
            pool = Executors.newFixedThreadPool(1);
        }
        while (socketChannel.isOpen()) {
            polling();
        }
    }

    public void asyncListen() throws IOException {
        StringBuilder sb = new StringBuilder("Thread [Client ");
        sb.append(socketChannel.getLocalAddress()).append(" to server ").append(socketChannel.getRemoteAddress())
                .append(" listener]");
        new Thread(sb.toString()) {
            public void run() {
                try {
                    listen();
                } catch (IOException e) {
                }
            }
        }.start();
    }

    public SocketClient sendMessage(String message) throws IOException {
        return sendMessage(message.getBytes());
    }

    public SocketClient sendMessage(byte[] message) throws IOException {
        if (null != socketChannel) {
            socketChannel.write(ByteBuffer.wrap(message));
        }
        return this;
    }

    public void reConnect() throws IOException {
        if (socketChannel.isOpen()) {
            socketChannel.connect(socketChannel.getRemoteAddress());
        }
    }

    public void close() throws IOException {
        if (socketChannel.isOpen()) {
            socketChannel.close();
        }
        super.close();
    }

    public SocketChannel getSocketChannel() {
        return socketChannel;
    }
}
