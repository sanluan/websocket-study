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
        setRunning(true);
        ByteBuffer byteBuffer = byteBufferQueue.poll();
        while (null != byteBuffer && !closed) {
            ProtocolHandler<T> protocolHandler = channelContext.getProtocolHandler();
            try {
                MultiByteBuffer multiByteBuffer = channelContext.getCachedBuffer();
                if (null == multiByteBuffer) {
                    multiByteBuffer = new MultiByteBuffer();
                    channelContext.setCachedBuffer(multiByteBuffer);
                }
                byteBuffer.flip();
                multiByteBuffer.put(byteBuffer);
                if (channelContext.getPayloadLength() <= multiByteBuffer.remaining()) {
                    protocolHandler.read(channelContext, multiByteBuffer);
                    if (multiByteBuffer.hasRemaining()) {
                        if (multiByteBuffer.size() > 1) {
                            multiByteBuffer.clear().put(byteBuffer);
                        }
                    } else {
                        multiByteBuffer.clear();
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
        setRunning(false);
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

    /**
     * @param running
     */
    private void setRunning(boolean running) {
        lock.lock();
        this.running = running;
        lock.unlock();
    }

    @Override
    public void close() throws IOException {
        closed = true;
    }
}
