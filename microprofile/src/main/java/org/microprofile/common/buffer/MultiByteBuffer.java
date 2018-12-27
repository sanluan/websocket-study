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
    private int currentStartPosition = 0;

    /**
     * @return
     */
    public byte get() {
        return byteBufferList.get(nextGetByteBufferIndex()).get();
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
        if ((position > limit) || (position < 0)) {
            throw new IllegalArgumentException();
        }
        int length = position > this.position ? position - this.position : this.position - position;
        ByteBuffer currentByteBuffer = byteBufferList.get(byteBufferIndex);
        while (length > 0) {
            Integer startOrEnd;
            int temp;
            if (position > this.position) {
                startOrEnd = currentByteBuffer.limit();
                temp = length - currentByteBuffer.remaining();
            } else {
                startOrEnd = startPositionMap.get(byteBufferIndex);
                if (null == startOrEnd) {
                    temp = length - currentByteBuffer.position();
                } else {
                    temp = length - (currentByteBuffer.position() - startOrEnd);
                }
            }
            if (temp > 0) {
                if (position > this.position) {
                    byteBufferIndex++;
                    currentStartPosition += currentByteBuffer.remaining();
                } else {
                    currentStartPosition -= currentByteBuffer.remaining();
                    byteBufferIndex--;
                }
                currentByteBuffer.position(startOrEnd);
                currentByteBuffer = byteBufferList.get(byteBufferIndex);
            } else {
                if (position > this.position) {
                    currentByteBuffer.position(currentByteBuffer.position() + length);
                } else {
                    currentByteBuffer.position(currentByteBuffer.position() - length);
                }
            }
            length = temp;
        }
        this.position = position;
    }

    /**
     * @param byteBuffer
     */
    public void put(ByteBuffer byteBuffer) {
        byteBufferList.add(byteBuffer);
        limit += byteBuffer.remaining();
        if (byteBuffer.position() > 0) {
            startPositionMap.put(byteBufferList.size() - 1, byteBuffer.position());
        }
    }

    /**
     * @return
     */
    private int nextGetByteBufferIndex() {
        if (position >= limit) {
            throw new BufferUnderflowException();
        }
        Integer start = startPositionMap.get(byteBufferIndex);
        boolean flag;
        if (start != null) {
            flag = position == currentStartPosition + byteBufferList.get(byteBufferIndex).limit() - start;
        } else {
            flag = position == currentStartPosition + byteBufferList.get(byteBufferIndex).limit();
        }
        if (flag) {
            currentStartPosition = position;
            byteBufferIndex++;
        }
        position++;
        return byteBufferIndex;
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
        StringBuffer sb = new StringBuffer();
        sb.append(getClass().getName());
        sb.append("[pos=");
        sb.append(position());
        sb.append(" lim=");
        sb.append(limit());
        sb.append(" index=");
        sb.append(byteBufferIndex);
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