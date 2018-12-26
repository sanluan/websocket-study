package org.microprofile.nio.handler;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ThreadHandler<T> implements Runnable, Closeable {
    private ChannelContext<T> channelContext;
    private ConcurrentLinkedQueue<ByteBuffer> byteBufferQueue = new ConcurrentLinkedQueue<>();
    private Lock lock = new ReentrantLock();
    private boolean running;
    private boolean closed;

    public ThreadHandler(ChannelContext<T> channelContext) {
        this.channelContext = channelContext;
    }

    public void addByteBuffer(ByteBuffer byteBuffer) {
        byteBufferQueue.add(byteBuffer);
    }

    @Override
    public void run() {
        lock.lock();
        running = true;
        lock.unlock();
        ByteBuffer byteBuffer = byteBufferQueue.poll();
        while (null != byteBuffer && !closed) {
            ProtocolHandler<T> protocolHandler = channelContext.getProtocolHandler();
            try {
                protocolHandler.read(channelContext, byteBuffer);
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
        lock.unlock();
    }

    /**
     * @return the running
     */
    public boolean isRunning() {
        lock.lock();
        boolean result = running;
        lock.unlock();
        return result;
    }

    @Override
    public void close() throws IOException {
        closed = true;
    }
}
