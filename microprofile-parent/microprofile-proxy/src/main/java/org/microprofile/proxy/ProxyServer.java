package org.microprofile.proxy;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.Executors;

import org.microprofile.nio.SocketServer;
import org.microprofile.proxy.handler.ServerProtocolHandler;

public class ProxyServer implements Closeable {
    private SocketServer socketServer;

    public ProxyServer(int port, int poolSize, int maxPending) throws IOException {
        this(null, port, poolSize, maxPending);
    }

    public ProxyServer(String host, int port, int poolSize, int maxPending) throws IOException {
        this.socketServer = new SocketServer(host, port, Executors.newFixedThreadPool(poolSize), new ServerProtocolHandler(),
                maxPending);
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
