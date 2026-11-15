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
        this(null, port, poolSize, messageHandler, maxPending, null, false);
    }

    public WebSocketServer(String host, int port, int poolSize, MessageHandler messageHandler, int maxPending)
            throws IOException {
        this(host, port, poolSize, messageHandler, maxPending, null, false);
    }

    public WebSocketServer(int port, int poolSize, MessageHandler messageHandler, int maxPending, SSLContext sslContext,
            boolean needClientAuth) throws IOException {
        this(null, port, poolSize, messageHandler, maxPending, sslContext, needClientAuth);
    }

    /**
     * @param host
     * @param port
     * @param poolSize
     * @param messageHandler
     * @param maxPending
     * @param sslContext
     *            null 表示不启用 TLS（ws），非 null 时监听 wss
     * @param needClientAuth
     *            是否要求客户端提供证书（双向认证）
     * @throws IOException
     */
    public WebSocketServer(String host, int port, int poolSize, MessageHandler messageHandler, int maxPending, SSLContext sslContext,
            boolean needClientAuth) throws IOException {
        if (null == messageHandler) {
            throw new IllegalArgumentException("messageHandler can't be null");
        }
        this.socketServer = new SocketServer(host, port, Executors.newFixedThreadPool(poolSize),
                new WebSocketProtocolHandler(messageHandler), maxPending, sslContext, needClientAuth);
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
