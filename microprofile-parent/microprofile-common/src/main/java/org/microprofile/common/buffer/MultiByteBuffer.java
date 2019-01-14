package org.microprofile.common.buffer;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.InvalidMarkException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

public class MultiByteBuffer {
    private LinkedList<ByteBuffer> byteBufferList = new LinkedList<>();
    private Map<Integer, Integer> startPositionMap = new HashMap<>();
    private int position = 0, limit = 0, index = 0, currentLimit = 0, mark = -1, indexMark = -1;
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
        int index = this.index;
        if (i > position) {
            int currentLimit = this.currentLimit;
            while (i >= currentLimit) {
                byteBuffer = byteBufferList.get(++index);
                currentLimit += byteBuffer.remaining();
            }
            return byteBuffer.get(i - currentLimit + byteBuffer.remaining());
        } else if (i < position) {
            Integer start = startPositionMap.get(index);
            int lastLimit = null == start ? this.currentLimit - byteBuffer.limit()
                    : this.currentLimit - byteBuffer.limit() + start;
            while (i < lastLimit) {
                byteBuffer = byteBufferList.get(--index);
                start = startPositionMap.get(index);
                lastLimit -= null == start ? byteBuffer.limit() : byteBuffer.limit() - start;
            }
            return byteBuffer.get(i - lastLimit + byteBuffer.remaining());
        } else {
            if (position == currentLimit) {
                byteBuffer = byteBufferList.get(index + 1);
            }
            return byteBuffer.get(byteBuffer.position());
        }
    }

    /**
     * @return
     */
    public int limit() {
        return limit;
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
        indexMark = index;
        if (null != byteBuffer) {
            byteBuffer.mark();
        }
        return this;
    }

    /**
     * @return
     */
    public MultiByteBuffer reset() {
        if (0 > mark) {
            throw new InvalidMarkException();
        }
        position = mark;
        while (indexMark < index) {
            byteBufferList.get(--index).reset();
        }
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
            index++;
            byteBuffer = byteBufferList.get(index);
            if (0 <= indexMark && indexMark <= index) {
                byteBuffer.mark();
            }
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

    public MultiByteBuffer clear() {
        mark = indexMark = -1;
        if (0 < index) {
            startPositionMap.clear();
            if (position < limit) {
                limit -= position;
                position = 0;
                currentLimit = byteBuffer.remaining();
                while (0 < index) {
                    index--;
                    byteBufferList.remove();
                }
                int i = 0;
                for (ByteBuffer byteBuffer : byteBufferList) {
                    if (0 < byteBuffer.position()) {
                        startPositionMap.put(i++, byteBuffer.position());
                    }
                }
            } else {
                position = limit = index = currentLimit = 0;
                byteBuffer = null;
                if (!byteBufferList.isEmpty()) {
                    byteBufferList.clear();
                }
            }
        }
        return this;
    }

    public int size() {
        return byteBufferList.size();
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getName());
        sb.append("[pos=");
        sb.append(position);
        sb.append(" lim=");
        sb.append(limit);
        sb.append(" index=");
        sb.append(index);
        sb.append(" size =");
        sb.append(byteBufferList.size());
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