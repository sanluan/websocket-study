package org.microprofile.nio;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.ExecutorService;

import javax.net.ssl.SSLContext;

import org.microprofile.nio.handler.ProtocolHandler;
import org.microprofile.nio.handler.SocketProcesser;

/**
 *
 */
public class SocketServer extends SocketProcesser implements Closeable {
    private ServerSocketChannel serverSocketChannel;
    private SocketAddress socketAddress;

    public SocketServer(String host, int port, ExecutorService pool, ProtocolHandler<?> protocolHandler) throws IOException {
        this(host, port, pool, protocolHandler, null, 0);
    }

    public SocketServer(String host, int port, ExecutorService pool, ProtocolHandler<?> protocolHandler, SSLContext sslContext)
            throws IOException {
        this(host, port, pool, protocolHandler, sslContext, 0);
    }

    public SocketServer(int port, ExecutorService pool, ProtocolHandler<?> protocolHandler) throws IOException {
        this(null, port, pool, protocolHandler, null, 0);
    }

    public SocketServer(int port, ExecutorService pool, ProtocolHandler<?> protocolHandler, SSLContext sslContext)
            throws IOException {
        this(null, port, pool, protocolHandler, sslContext, 0);
    }

    public SocketServer(int port, ExecutorService pool, ProtocolHandler<?> protocolHandler, int maxPending) throws IOException {
        this(null, port, pool, protocolHandler, null, maxPending);
    }

    public SocketServer(int port, ExecutorService pool, ProtocolHandler<?> protocolHandler, SSLContext sslContext, int maxPending)
            throws IOException {
        this(null, port, pool, protocolHandler, sslContext, maxPending);
    }

    public SocketServer(String host, int port, ExecutorService pool, ProtocolHandler<?> protocolHandler, SSLContext sslContext,
            int maxPending) throws IOException {
        this(null == host ? new InetSocketAddress(port) : new InetSocketAddress(host, port), pool, protocolHandler, sslContext,
                maxPending);
    }

    public SocketServer(SocketAddress socketAddress, ExecutorService pool, ProtocolHandler<?> protocolHandler,
            SSLContext sslContext, int maxPending) throws IOException {
        super(pool, protocolHandler, null == sslContext ? null : sslContext.createSSLEngine(), maxPending);
        this.socketAddress = socketAddress;
        this.serverSocketChannel = ServerSocketChannel.open();
        ServerSocket serverSocket = serverSocketChannel.socket();
        serverSocket.bind(socketAddress);
        serverSocketChannel.configureBlocking(false).register(selector, SelectionKey.OP_ACCEPT);
    }

    public boolean isOpen() throws IOException {
        return serverSocketChannel.isOpen();
    }

    public String getName() {
        StringBuilder sb = new StringBuilder("Thread [Server ");
        sb.append(socketAddress).append(" listener]");
        return sb.toString();
    }

    @Override
    public void close() throws IOException {
        if (serverSocketChannel.isOpen()) {
            serverSocketChannel.close();
        }
        super.close();
    }

    public SocketAddress getSocketAddress() {
        return socketAddress;
    }

}