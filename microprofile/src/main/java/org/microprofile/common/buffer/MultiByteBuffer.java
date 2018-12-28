package org.microprofile.common.buffer;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class MultiByteBuffer {
    private List<ByteBuffer> byteBufferList = new LinkedList<>();
    private Map<Integer, Integer> startPositionMap = new HashMap<>();
    private int position = 0;
    private int limit = 0;
    private int byteBufferIndex = 0;
    private int currentLimit = 0;
    private ByteBuffer byteBuffer;

    /**
     * @return
     */
    public byte get() {
        return nextGetByteBuffer().get();
    }

    public byte get(int i) {
        if (0 > i || limit <= i) {
            throw new IndexOutOfBoundsException();
        }
        if (i > position) {
            int currentLimit = this.currentLimit;
            int byteBufferIndex = this.byteBufferIndex;
            ByteBuffer byteBuffer = this.byteBuffer;
            while (i >= currentLimit) {
                byteBuffer = byteBufferList.get(++byteBufferIndex);
                currentLimit += byteBuffer.remaining();
            }
            return byteBuffer.get(i - currentLimit + byteBuffer.remaining());
        } else if (i < position) {
            int byteBufferIndex = this.byteBufferIndex;
            ByteBuffer byteBuffer = this.byteBuffer;
            int lastLimit = this.currentLimit - byteBuffer.limit() + startPositionMap.get(byteBufferIndex);
            while (i < lastLimit) {
                if (byteBufferIndex == 0) {
                    System.out.println(1);
                }
                byteBuffer = byteBufferList.get(--byteBufferIndex);
                lastLimit -= byteBuffer.limit()-startPositionMap.get(byteBufferIndex);
            }
            return byteBuffer.get(i - lastLimit + byteBuffer.remaining());
        } else {
            ByteBuffer byteBuffer = this.byteBuffer;
            if (position == currentLimit) {
                byteBuffer = byteBufferList.get(byteBufferIndex + 1);
            }
            return byteBuffer.get(byteBuffer.position());
        }
    }

    /**
     * @return
     */
    public int position() {
        return position;
    }

    /**
     * @return
     */
    public int limit() {
        return limit;
    }

    /**
     * @param position
     */
    public void position(int position) {
        if (0 > position || position > limit) {
            throw new IllegalArgumentException();
        }
        boolean increase = position > this.position;
        int length = increase ? position - this.position : this.position - position;
        ByteBuffer currentByteBuffer = byteBuffer;
        while (length > 0) {
            Integer currentStart = null;
            if (!increase) {
                currentStart = startPositionMap.get(byteBufferIndex);
            }
            int temp = increase ? length - currentByteBuffer.remaining() : length - (currentByteBuffer.position() - currentStart);
            if (temp > 0) {
                if (increase) {
                    currentByteBuffer.position(currentByteBuffer.limit());
                    byteBufferIndex++;
                } else {
                    currentByteBuffer.position(currentStart);
                    byteBufferIndex--;
                }
                byteBuffer = currentByteBuffer = byteBufferList.get(byteBufferIndex);
            } else {
                currentByteBuffer.position(currentByteBuffer.position() + (increase ? length : -length));
            }
            length = temp;
        }
        this.position = position;
        this.currentLimit = position + currentByteBuffer.remaining();
    }

    /**
     * @param byteBuffer
     * @return
     */
    public MultiByteBuffer put(ByteBuffer byteBuffer) {
        if (byteBufferList.isEmpty()) {
            currentLimit = byteBuffer.limit();
            this.byteBuffer = byteBuffer;
        }
        startPositionMap.put(byteBufferList.size(), byteBuffer.position());
        limit += byteBuffer.remaining();
        byteBufferList.add(byteBuffer);
        return this;
    }

    /**
     * @param byteBuffers
     * @return
     */
    public MultiByteBuffer put(ByteBuffer... byteBuffers) {
        if (byteBufferList.isEmpty() && byteBuffers.length > 0) {
            currentLimit = byteBuffers[0].limit();
            this.byteBuffer = byteBuffers[0];
        }
        for (ByteBuffer byteBuffer : byteBuffers) {
            startPositionMap.put(byteBufferList.size(), byteBuffer.position());
            limit += byteBuffer.remaining();
            byteBufferList.add(byteBuffer);
        }
        return this;
    }

    /**
     * @return
     */
    private ByteBuffer nextGetByteBuffer() {
        if (position >= limit) {
            throw new BufferUnderflowException();
        }
        if (position == currentLimit) {
            byteBufferIndex++;
            byteBuffer = byteBufferList.get(byteBufferIndex);
            currentLimit = position + byteBuffer.remaining();
        }
        position++;
        return byteBuffer;

    }

    /**
     * @param dst
     * @return
     */
    public MultiByteBuffer get(byte[] dst) {
        return get(dst, 0, dst.length);
    }

    /**
     * @return
     */
    public final boolean hasRemaining() {
        return position < limit;
    }

    /**
     * @param off
     * @param len
     * @param size
     */
    private static void checkBounds(int off, int len, int size) {
        if ((off | len | (off + len) | (size - (off + len))) < 0) {
            throw new IndexOutOfBoundsException();
        }
    }

    /**
     * @param dst
     * @param offset
     * @param length
     * @return
     */
    public MultiByteBuffer get(byte[] dst, int offset, int length) {
        checkBounds(offset, length, dst.length);
        if (length > remaining()) {
            throw new BufferUnderflowException();
        }
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            dst[i] = get();
        }
        return this;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getName());
        sb.append("[pos=");
        sb.append(position());
        sb.append(" lim=");
        sb.append(limit());
        sb.append(" index=");
        sb.append(byteBufferIndex);
        sb.append(" size =");
        sb.append(byteBufferList.size());
        sb.append(" currentLimit =");
        sb.append(currentLimit);
        sb.append("]");
        return sb.toString();
    }

    /**
     * @return
     */
    public final int remaining() {
        return limit - position;
    }
}