package org.microprofile.nio.handler;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.microprofile.common.buffer.MultiByteBuffer;

public class ThreadHandler<T> implements Runnable, Closeable {
    private ChannelContext<T> channelContext;
    private ConcurrentLinkedQueue<ByteBuffer> byteBufferQueue = new ConcurrentLinkedQueue<>();
    private boolean running;
    private boolean closed;
    private MultiByteBuffer cachedBuffer = new MultiByteBuffer();
    private int payloadLength = 0;
    private Lock lock = new ReentrantLock();

    public ThreadHandler(ChannelContext<T> channelContext) {
        this.channelContext = channelContext;
    }

    public boolean addByteBuffer(ByteBuffer byteBuffer) {
        byteBufferQueue.add(byteBuffer);
        return running;
    }

    @Override
    public void run() {
        lock.lock();
        if (!running) {
            running = true;
            lock.unlock();
            ByteBuffer byteBuffer = byteBufferQueue.poll();
            while (null != byteBuffer && !closed) {
                ProtocolHandler<T> protocolHandler = channelContext.getProtocolHandler();
                byteBuffer.flip();
                cachedBuffer.put(byteBuffer);
                try {
                    if (payloadLength <= cachedBuffer.remaining()) {
                        protocolHandler.read(channelContext, cachedBuffer);
                        if (cachedBuffer.hasRemaining()) {
                            if (0 < payloadLength && 0 < cachedBuffer.size()) {
                                cachedBuffer.clear().put(byteBuffer);
                            }
                        } else {
                            cachedBuffer.clear();
                        }
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
        }else {
            System.out.println(1);
        }
        lock.unlock();
    }

    /**
     * @return the payloadLength
     */
    public int getPayloadLength() {
        return payloadLength;
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