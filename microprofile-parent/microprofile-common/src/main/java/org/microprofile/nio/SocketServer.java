package org.microprofile.nio;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.microprofile.nio.handler.ChannelContext;
import org.microprofile.nio.handler.ProtocolHandler;
import org.microprofile.nio.handler.SocketProcesser;

/**
 * @author zhangxdr
 *
 */
public class SocketServer extends SocketProcesser implements Closeable {
    private ServerSocketChannel serverSocketChannel;
    private SocketAddress socketAddress;
    private Selector acceptSelector;

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
        this.acceptSelector = Selector.open();
    }

    public void listen() throws IOException {
        ServerSocket serverSocket = serverSocketChannel.socket();
        serverSocket.bind(socketAddress);
        serverSocketChannel.configureBlocking(false);
        if (null == pool) {
            pool = Executors.newFixedThreadPool(1);
        }
        StringBuilder sb = new StringBuilder("Thread [Server ");
        sb.append(socketAddress).append(" accepter]");
        new Thread(sb.toString()) {
            public void run() {
                try {
                    serverSocketChannel.register(acceptSelector, SelectionKey.OP_ACCEPT);
                    accept();
                } catch (IOException e) {
                }
            }
        }.start();
        while (serverSocketChannel.isOpen()) {
            polling();
        }
    }

    public void accept() throws IOException {
        while (serverSocketChannel.isOpen()) {
            if (0 < acceptSelector.select()) {
                Iterator<SelectionKey> keyIterator = acceptSelector.selectedKeys().iterator();
                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    keyIterator.remove();
                    if (key.isReadable()) {
                        ServerSocketChannel server = (ServerSocketChannel) key.channel();
                        SocketChannel socketChannel = server.accept();
                        register(socketChannel.configureBlocking(false), new ChannelContext<>(protocolHandler, socketChannel));
                    }
                }
            }
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