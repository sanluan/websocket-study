package org.microprofile.nio.handler;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class ChannelContext {
    private SelectionKey key;
    private SocketChannel socketChannel;

    public ChannelContext(SelectionKey key, SocketChannel socketChannel) {
        this.key = key;
        this.socketChannel = socketChannel;
    }

    /**
     * @return the key
     */
    public SelectionKey getKey() {
        return key;
    }

    /**
     * @return the client
     */
    public SocketChannel getSocketChannel() {
        return socketChannel;
    }
}
