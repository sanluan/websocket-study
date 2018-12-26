package org.microprofile.nio.handler;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class ChannelContext<T> implements Closeable {
    private SelectionKey key;
    private SocketChannel socketChannel;
    private ProtocolHandler<T> protocolHandler;
    private ThreadHandler<T> threadHandler;
    private boolean closed;
    private T attachment;

    public ChannelContext(SelectionKey key, ProtocolHandler<T> protocolHandler, SocketChannel socketChannel) {
        this.key = key;
        this.socketChannel = socketChannel;
        this.protocolHandler = protocolHandler;
        this.threadHandler = new ThreadHandler<>(this);
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            key.cancel();
            threadHandler.close();
            protocolHandler.close(this);
            socketChannel.close();
        }
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

    /**
     * @return the protocolHandler
     */
    public ProtocolHandler<T> getProtocolHandler() {
        return protocolHandler;
    }

    /**
     * @return the threadHandler
     */
    public ThreadHandler<T> getThreadHandler() {
        return threadHandler;
    }

    /**
     * @return the closed
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * @return the attachment
     */
    public T getAttachment() {
        return attachment;
    }

    /**
     * @param attachment
     *            the attachment to set
     */
    public void setAttachment(T attachment) {
        this.attachment = attachment;
    }

}
