package org.microprofile.common.buffer;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.InvalidMarkException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class MultiByteBuffer {
    private List<ByteBuffer> byteBufferList = new LinkedList<>();
    private Map<Integer, Integer> startPositionMap = new HashMap<>();
    private int position = 0, limit = 0, byteBufferIndex = 0, currentLimit = 0, mark = -1;
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
        ByteBuffer byteBuffer = this.byteBuffer;
        int byteBufferIndex = this.byteBufferIndex;
        if (i > position) {
            int currentLimit = this.currentLimit;
            while (i >= currentLimit) {
                byteBuffer = byteBufferList.get(++byteBufferIndex);
                currentLimit += byteBuffer.remaining();
            }
            return byteBuffer.get(i - currentLimit + byteBuffer.remaining());
        } else if (i < position) {
            Integer start = startPositionMap.get(byteBufferIndex);
            int lastLimit = null == start ? this.currentLimit - byteBuffer.limit()
                    : this.currentLimit - byteBuffer.limit() + start;
            while (i < lastLimit) {
                byteBuffer = byteBufferList.get(--byteBufferIndex);
                start = startPositionMap.get(byteBufferIndex);
                lastLimit -= null == start ? byteBuffer.limit() : byteBuffer.limit() - start;
            }
            return byteBuffer.get(i - lastLimit + byteBuffer.remaining());
        } else {
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
        while (0 < length) {
            Integer currentStart = null;
            if (!increase) {
                currentStart = startPositionMap.get(byteBufferIndex);
            }
            int temp = increase ? length - currentByteBuffer.remaining()
                    : null == currentStart ? length - currentByteBuffer.position()
                            : length - (currentByteBuffer.position() - currentStart);
            if (0 < temp) {
                if (increase) {
                    currentByteBuffer.position(currentByteBuffer.limit());
                    byteBufferIndex++;
                } else {
                    currentByteBuffer.position(null == currentStart ? 0 : currentStart);
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
        if (null == this.byteBuffer) {
            currentLimit = byteBuffer.remaining();
            this.byteBuffer = byteBuffer;
        }
        if (0 < byteBuffer.position()) {
            startPositionMap.put(byteBufferList.size(), byteBuffer.position());
        }
        limit += byteBuffer.remaining();
        byteBufferList.add(byteBuffer);
        return this;
    }

    /**
     * @param byteBuffers
     * @return
     */
    public MultiByteBuffer put(ByteBuffer... byteBuffers) {
        for (ByteBuffer byteBuffer : byteBuffers) {
            put(byteBuffer);
        }
        return this;
    }

    /**
     * @return
     */
    public MultiByteBuffer mark() {
        mark = position;
        return this;
    }

    /**
     * @return
     */
    public MultiByteBuffer reset() {
        if (mark < 0) {
            throw new InvalidMarkException();
        }
        position(mark);
        mark = 0;
        return this;
    }

    /**
     * @return
     */
    private ByteBuffer nextGetByteBuffer() {
        if (position >= currentLimit) {
            if (position >= limit) {
                throw new BufferUnderflowException();
            }
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
        if (0 > (off | len | (off + len) | (size - (off + len)))) {
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

    public MultiByteBuffer clear(Collection<ByteBuffer> recycleCollection) {
        position = limit = byteBufferIndex = currentLimit = 0;
        mark = -1;
        byteBuffer = null;
        if (!byteBufferList.isEmpty()) {
            if (null != recycleCollection) {
                recycleCollection.addAll(byteBufferList);
            }
            byteBufferList.clear();
        }
        startPositionMap.clear();
        return this;
    }

    public int size() {
        return byteBufferList.size();
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