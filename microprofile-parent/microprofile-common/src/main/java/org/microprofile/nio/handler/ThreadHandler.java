package org.microprofile.nio.handler;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.microprofile.common.buffer.MultiByteBuffer;

public class ThreadHandler<T> implements Runnable, Closeable {
    private SocketProcesser socketProcesser;
    private ChannelContext<T> channelContext;
    private ConcurrentLinkedQueue<ByteBuffer> byteBufferQueue = new ConcurrentLinkedQueue<>();
    private boolean running;
    private boolean closed;
    private MultiByteBuffer cachedBuffer = new MultiByteBuffer();
    private int payloadLength = 0;
    private Lock lock = new ReentrantLock();

    /**
     * @param channelContext
     * @param socketProcesser
     */
    public ThreadHandler(ChannelContext<T> channelContext, SocketProcesser socketProcesser) {
        this.channelContext = channelContext;
        this.socketProcesser = socketProcesser;
    }

    /**
     * @param byteBuffer
     * @return
     */
    public boolean addByteBuffer(ByteBuffer byteBuffer) {
        byteBufferQueue.add(byteBuffer);
        socketProcesser.add();
        return !running;
    }

    @Override
    public void run() {
        lock.lock();
        if (!running) {
            running = true;
            lock.unlock();
            ByteBuffer byteBuffer = byteBufferQueue.poll();
            while (null != byteBuffer && !closed) {
                socketProcesser.minus();
                ProtocolHandler<T> protocolHandler = channelContext.getProtocolHandler();
                cachedBuffer.put(byteBuffer);
                try {
                    if (payloadLength <= cachedBuffer.remaining()) {
                        protocolHandler.read(channelContext, cachedBuffer);
                        cachedBuffer.clear(socketProcesser.getRecycleByteBufferQueue());
                    }
                } catch (IOException e) {
                    try {
                        channelContext.close();
                    } catch (IOException e1) {
                    }
                }
                byteBuffer = byteBufferQueue.poll();
            }
            lock.lock();
            running = false;
        }
        lock.unlock();
    }

    /**
     * @param payloadLength
     *            the payloadLength to set
     */
    public void setPayloadLength(int payloadLength) {
        this.payloadLength = payloadLength;
    }

    @Override
    public void close() throws IOException {
        closed = true;
    }

}
