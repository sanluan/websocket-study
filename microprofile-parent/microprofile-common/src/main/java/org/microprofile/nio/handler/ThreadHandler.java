package org.microprofile.nio.handler;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.microprofile.common.buffer.MultiByteBuffer;

public class ThreadHandler<T> implements Runnable, Closeable {
    private ChannelContext<T> channelContext;
    private ConcurrentLinkedQueue<ByteBuffer> byteBufferQueue = new ConcurrentLinkedQueue<>();
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
        this.running = true;
        ByteBuffer byteBuffer = byteBufferQueue.poll();
        while (null != byteBuffer && !closed) {
            ProtocolHandler<T> protocolHandler = channelContext.getProtocolHandler();
            MultiByteBuffer multiByteBuffer = channelContext.getCachedBuffer();
            if (null == multiByteBuffer) {
                multiByteBuffer = new MultiByteBuffer();
                channelContext.setCachedBuffer(multiByteBuffer);
            }
            byteBuffer.flip();
            multiByteBuffer.put(byteBuffer);
            try {
                int payloadLength = channelContext.getPayloadLength();
                if (payloadLength <= multiByteBuffer.remaining()) {
                    protocolHandler.read(channelContext, multiByteBuffer);
                    if (multiByteBuffer.hasRemaining()) {
                        if (0 < payloadLength && 0 < multiByteBuffer.size()) {
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
        this.running = false;
    }

    /**
     * @return the running
     */
    public boolean isRunning() {
        return running;
    }

    @Override
    public void close() throws IOException {
        closed = true;
    }
}
