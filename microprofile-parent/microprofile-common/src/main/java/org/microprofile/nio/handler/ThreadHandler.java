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
    private volatile boolean running;
    private volatile boolean closed;
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
     * 入队并在锁内抢占消费权，避免"最后一个字节入队后线程刚好退出"导致的丢失唤醒
     *
     * @param byteBuffer
     * @return true 需要提交到线程池执行
     */
    public boolean addByteBuffer(ByteBuffer byteBuffer) {
        socketProcesser.add();
        byteBufferQueue.add(byteBuffer);
        lock.lock();
        try {
            if (running) {
                return false;
            }
            running = true;
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void run() {
        for (;;) {
            ByteBuffer byteBuffer = byteBufferQueue.poll();
            if (null == byteBuffer) {
                boolean empty;
                lock.lock();
                try {
                    empty = closed || byteBufferQueue.isEmpty();
                    if (empty) {
                        running = false;
                    }
                } finally {
                    lock.unlock();
                }
                if (empty) {
                    return;
                }
                continue;
            }
            try {
                socketProcesser.minus();
                ProtocolHandler<T> protocolHandler = channelContext.getProtocolHandler();
                cachedBuffer.put(byteBuffer);
                if (payloadLength <= cachedBuffer.remaining()) {
                    protocolHandler.read(channelContext, cachedBuffer);
                    cachedBuffer.clear(socketProcesser.getRecycleByteBufferQueue());
                }
            } catch (Exception e) {
                try {
                    channelContext.close();
                } catch (Exception e1) {
                }
            }
        }
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
