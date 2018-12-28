package org.microprofile.nio;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.microprofile.nio.handler.ProtocolHandler;
import org.microprofile.nio.handler.SocketProcesser;

/**
 * @author zhangxdr
 *
 */
public class SocketServer extends SocketProcesser implements Closeable {
    private ServerSocketChannel serverSocketChannel;
    private SocketAddress socketAddress;

    public SocketServer(int port, ExecutorService pool, ProtocolHandler<?> protocolHandler) throws IOException {
        this(null, port, pool, protocolHandler);
    }

    public SocketServer(String host, int port, ExecutorService pool, ProtocolHandler<?> protocolHandler) throws IOException {
        this(null == host ? new InetSocketAddress(port) : new InetSocketAddress(host, port), pool, protocolHandler);
    }

    public SocketServer(SocketAddress socketAddress, ExecutorService pool, ProtocolHandler<?> protocolHandler)
            throws IOException {
        super(pool, protocolHandler);
        this.socketAddress = socketAddress;
        this.serverSocketChannel = ServerSocketChannel.open();
    }

    public void listen() throws IOException {
        ServerSocket serverSocket = serverSocketChannel.socket();
        serverSocket.bind(socketAddress);
        serverSocketChannel.configureBlocking(false);
        if (null == pool) {
            pool = Executors.newFixedThreadPool(1);
        }
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
        while (serverSocketChannel.isOpen()) {
            polling();
        }
    }

    public void asyncListen() throws IOException {
        StringBuilder sb = new StringBuilder("Thread [Server ");
        sb.append(socketAddress).append(" listener]");
        new Thread(sb.toString()) {
            public void run() {
                try {
                    listen();
                } catch (IOException e) {
                }
            }
        }.start();
    }

    @Override
    public void close() throws IOException {
        if (serverSocketChannel.isOpen()) {
            serverSocketChannel.close();
        }
        super.close();
    }

    public ProtocolHandler<?> getProtocolHandler() {
        return protocolHandler;
    }

    public SocketAddress getSocketAddress() {
        return socketAddress;
    }

}