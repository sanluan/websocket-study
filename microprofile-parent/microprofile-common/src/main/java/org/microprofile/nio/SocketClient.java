package org.microprofile.nio;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.microprofile.nio.handler.ChannelContext;
import org.microprofile.nio.handler.ProtocolHandler;
import org.microprofile.nio.handler.SocketProcesser;

public class SocketClient extends SocketProcesser implements Closeable {
    protected ChannelContext<?> channelContext;

    public SocketClient(String host, int port, ExecutorService pool, ProtocolHandler<?> protocolHandler) throws IOException {
        this(host, port, pool, protocolHandler, 0);
    }

    public SocketClient(String host, int port, ExecutorService pool, ProtocolHandler<?> protocolHandler, int maxPending)
            throws IOException {
        this(new InetSocketAddress(host, port), pool, protocolHandler, maxPending);
    }

    public SocketClient(SocketAddress socketAddress, ExecutorService pool, ProtocolHandler<?> protocolHandler, int maxPending)
            throws IOException {
        super(pool, protocolHandler, maxPending);
        SocketChannel socketChannel = SocketChannel.open(socketAddress);
        channelContext = new ChannelContext<>(protocolHandler, socketChannel);
        register(socketChannel.configureBlocking(false), channelContext);
    }

    public void listen() throws IOException {
        if (null == pool) {
            pool = Executors.newFixedThreadPool(1);
        }
        while (channelContext.isOpen()) {
            polling();
        }
    }

    public void asyncListen() throws IOException {
        StringBuilder sb = new StringBuilder("Thread [Client ");
        sb.append(channelContext.getSocketChannel().getLocalAddress()).append(" to server ")
                .append(channelContext.getSocketChannel().getRemoteAddress()).append(" listener]");
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
        if (null != channelContext) {
            channelContext.write(ByteBuffer.wrap(message));
        }
        return this;
    }

    public boolean isOpen() throws IOException {
        return channelContext.isOpen() && channelContext.getSocketChannel().finishConnect();
    }

    public void reConnect() throws IOException {
        if (!channelContext.isOpen()) {
            channelContext.getSocketChannel().connect(channelContext.getSocketChannel().getRemoteAddress());
        }
    }

    public void close() throws IOException {
        if (channelContext.isOpen()) {
            channelContext.close();
        }
        super.close();
    }

    public ChannelContext<?> getChannelContext() {
        return channelContext;
    }
}
