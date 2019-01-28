package org.microprofile.websocket;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;

import org.microprofile.nio.SocketServer;
import org.microprofile.websocket.handler.MessageHandler;
import org.microprofile.websocket.handler.WebSocketProtocolHandler;

public class WebSocketServer implements Closeable {
    private SocketServer socketServer;

    public WebSocketServer(int port, int poolSize, MessageHandler messageHandler, int maxPending) throws IOException {
        this(null, port, poolSize, messageHandler, null, maxPending);
    }

    public WebSocketServer(int port, int poolSize, MessageHandler messageHandler, SSLContext sslContext, int maxPending)
            throws IOException {
        this(null, port, poolSize, messageHandler, sslContext, maxPending);
    }

    public WebSocketServer(String host, int port, int poolSize, MessageHandler messageHandler, SSLContext sslContext,
            int maxPending) throws IOException {
        if (null == messageHandler) {
            throw new IllegalArgumentException("messageHandler can't be null");
        }
        this.socketServer = new SocketServer(host, port, Executors.newFixedThreadPool(poolSize),
                new WebSocketProtocolHandler(messageHandler), sslContext, maxPending);
    }

    public boolean isOpen() throws IOException {
        return socketServer.isOpen();
    }

    public void listen() throws IOException {
        socketServer.listen();
    }

    public void asyncListen() throws IOException {
        socketServer.asyncListen();
    }

    public void close() throws IOException {
        socketServer.close();
    }

}
