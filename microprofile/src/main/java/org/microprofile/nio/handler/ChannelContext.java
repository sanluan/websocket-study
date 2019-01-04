package org.microprofile.nio.handler;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import org.microprofile.common.buffer.MultiByteBuffer;

public class ChannelContext<T> implements Closeable {
    private SocketChannel socketChannel;
    private ProtocolHandler<T> protocolHandler;
    private ThreadHandler<T> threadHandler;
    private boolean closed;
    private T attachment;
    private MultiByteBuffer cachedBuffer;
    private int payloadLength;

    public ChannelContext(ProtocolHandler<T> protocolHandler, SocketChannel socketChannel) {
        this.socketChannel = socketChannel;
        this.protocolHandler = protocolHandler;
        this.threadHandler = new ThreadHandler<>(this);
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            threadHandler.close();
            protocolHandler.close(this);
        }
        if (socketChannel.isOpen()) {
            socketChannel.close();
        }
    }

    /**
     * @param src
     * @return
     * @throws IOException
     */
    public int write(ByteBuffer src) throws IOException {
        int i = 0;
        while (src.hasRemaining()) {
            i = socketChannel.write(src);
        }
        return i;
    }

    /**
     * @param message
     * @return
     * @throws IOException
     */
    public int write(String message) throws IOException {
        return write(message.getBytes());
    }

    /**
     * @param message
     * @return
     * @throws IOException
     */
    public int write(byte[] message) throws IOException {
        ByteBuffer byteBuffer = ByteBuffer.wrap(message);
        return write(byteBuffer);
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
    public boolean isOpen() {
        return socketChannel.isOpen();
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

    /**
     * @return
     */
    public MultiByteBuffer getCachedBuffer() {
        return cachedBuffer;
    }

    /**
     * @param cachedBuffer
     */
    public void setCachedBuffer(MultiByteBuffer cachedBuffer) {
        this.cachedBuffer = cachedBuffer;
    }

    /**
     * @return
     */
    public int getPayloadLength() {
        return payloadLength;
    }

    /**
     * @param payloadLength
     */
    public void setPayloadLength(int payloadLength) {
        this.payloadLength = payloadLength;
    }
}
