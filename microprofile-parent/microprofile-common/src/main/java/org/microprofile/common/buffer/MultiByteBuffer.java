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
        int index = indexOf(byteBuffer);
        if (i > position) {
            int currentLimit = this.currentLimit;
            while (i >= currentLimit) {
                byteBuffer = byteBufferList.get(++index);
                currentLimit += byteBuffer.remaining();
            }
            return byteBuffer.get(byteBuffer.position() + i - currentLimit + byteBuffer.remaining());
        } else if (i < position) {
            Integer start = startPositionMap.get(index);
            int lastLimit = null == start ? position - byteBuffer.position()
                    : position - byteBuffer.position() + start;
            while (i < lastLimit) {
                byteBuffer = byteBufferList.get(--index);
                start = startPositionMap.get(index);
                lastLimit -= null == start ? byteBuffer.limit() : byteBuffer.limit() - start;
            }
            return byteBuffer.get(byteBuffer.position() + i - lastLimit);
        } else {
            if (position == currentLimit && index + 1 < byteBufferList.size()) {
                byteBuffer = byteBufferList.get(++index);
            }
            return byteBuffer.get(byteBuffer.position());
        }
    }

    /**
     * @param target
     * @return 引用相等的下标，避免 ByteBuffer.equals 比较内容造成误判
     */
    private int indexOf(ByteBuffer target) {
        for (int i = 0, size = byteBufferList.size(); i < size; i++) {
            if (byteBufferList.get(i) == target) {
                return i;
            }
        }
        return -1;
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
        while (index > indexMark) {
            // 先把当前缓冲区回退到被切入时的位置，再退到上一个缓冲区
            byteBufferList.get(index).reset();
            index--;
        }
        position = mark;
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
        if (0 > offset || 0 > length || dst.length < offset + length) {
            throw new IndexOutOfBoundsException();
        }
        if (length > remaining()) {
            throw new BufferUnderflowException();
        }
        for (int i = offset, end = offset + length; i < end; i++) {
            if (null != byteBuffer && !byteBuffer.hasRemaining()) {
                next();
            }
            dst[i] = byteBuffer.get();
            position++;
        }
        return this;
    }

    /**
     * @param recycleCollection
     * @return
     */
    public MultiByteBuffer clear(Collection<ByteBuffer> recycleCollection) {
        mark = indexMark = -1;
        if (position < limit) {
            if (0 < index) {
                startPositionMap.clear();
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