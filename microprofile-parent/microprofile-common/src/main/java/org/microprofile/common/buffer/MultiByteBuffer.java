package org.microprofile.common.buffer;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.InvalidMarkException;
import java.util.Collection;
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
        index = indexMark;
        byteBuffer = byteBufferList.get(index);
        byteBuffer.reset();
        currentLimit = position + byteBuffer.remaining();
        return this;
    }

    /**
     * @return
     */
    private ByteBuffer nextGetByteBuffer() {
        if (position >= currentLimit) {
            if (position >= limit) {
                throw new BufferUnderflowException();
            } else {
                next();
            }
        }
        position++;
        return byteBuffer;
    }

    private void next() {
        byteBuffer = byteBufferList.get(++index);
        if (indexMark < index) {
            byteBuffer.mark();
        }
        currentLimit = position + byteBuffer.remaining();
    }

    /**
     * @param dst
     * @param offset
     * @param length
     * @return
     */
    public MultiByteBuffer get(byte[] dst, int offset, int length) {
        int len = length - offset;
        if (len > remaining()) {
            throw new BufferUnderflowException();
        } else {
            for (int i = offset; i < len; i++) {
                if (position >= currentLimit) {
                    next();
                }
                position++;
                dst[i] = byteBuffer.get();
            }
        }
        return this;
    }

    /**
     * @param recycleCollection
     * @return
     */
    public MultiByteBuffer clear(Collection<ByteBuffer> recycleCollection) {
        mark = indexMark = -1;
        if (0 < index) {
            startPositionMap.clear();
            if (position < limit) {
                limit -= position;
                position = 0;
                currentLimit = byteBuffer.remaining();
                while (0 < index) {
                    index--;
                    if (null == recycleCollection) {
                        byteBufferList.remove();
                    } else {
                        recycleCollection.add(byteBufferList.remove());
                    }
                }
                int i = 0;
                for (ByteBuffer byteBuffer : byteBufferList) {
                    if (0 < byteBuffer.position()) {
                        startPositionMap.put(i, byteBuffer.position());
                    }
                    i++;
                }
            } else {
                position = limit = index = currentLimit = 0;
                byteBuffer = null;
                if (!byteBufferList.isEmpty()) {
                    if (null != recycleCollection) {
                        recycleCollection.addAll(byteBufferList);
                    }
                    byteBufferList.clear();
                }
            }
        }
        return this;
    }

    public char getChar() {
        return (char) ((get() << 8) | (get() & 0xff));
    }

    public char getChar(int index) {
        return (char) ((get(index) << 8) | (get(index + 1) & 0xff));
    }

    public short getShort() {
        return (short) ((get() << 8) | (get() & 0xff));
    }

    public short getShort(int index) {
        return (short) ((get(index) << 8) | (get(index + 1) & 0xff));
    }

    public int getInt() {
        return (((get()) << 24) | ((get() & 0xff) << 16) | ((get() & 0xff) << 8) | ((get() & 0xff)));
    }

    public int getInt(int index) {
        return (((get(index)) << 24) | ((get(index + 1) & 0xff) << 16) | ((get(index + 2) & 0xff) << 8)
                | ((get(index + 3) & 0xff)));
    }

    public long getLong() {
        return ((((long) get()) << 56) | (((long) get() & 0xff) << 48) | (((long) get() & 0xff) << 40)
                | (((long) get() & 0xff) << 32) | (((long) get() & 0xff) << 24) | (((long) get() & 0xff) << 16)
                | (((long) get() & 0xff) << 8) | (((long) get() & 0xff)));
    }

    public long getLong(int index) {
        return ((((long) get(index)) << 56) | (((long) get(index + 1) & 0xff) << 48) | (((long) get(index + 2) & 0xff) << 40)
                | (((long) get(index + 3) & 0xff) << 32) | (((long) get(index + 4) & 0xff) << 24)
                | (((long) get(index + 5) & 0xff) << 16) | (((long) get(index + 6) & 0xff) << 8)
                | (((long) get(index + 7) & 0xff)));
    }

    public float getFloat() {
        return Float.intBitsToFloat(getInt());
    }

    public float getFloat(int index) {
        return Float.intBitsToFloat(getInt(index));
    }

    public double getDouble() {
        return Double.longBitsToDouble(getLong());
    }

    public double getDouble(int index) {
        return Double.longBitsToDouble(getLong(index));
    }

    /**
     * @return
     */
    public int size() {
        return byteBufferList.size();
    }

    /**
     * @return
     */
    public int limit() {
        return limit;
    }

    /**
     * @return
     */
    public final int remaining() {
        return limit - position;
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
}