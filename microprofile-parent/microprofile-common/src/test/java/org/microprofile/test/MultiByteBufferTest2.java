package org.microprofile.test;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.microprofile.common.buffer.MultiByteBuffer;

public class MultiByteBufferTest2 {

    public static void main(String[] args) {
        ConcurrentLinkedQueue<ByteBuffer> recycleByteBufferQueue = new ConcurrentLinkedQueue<>();
        MultiByteBuffer multiByteBuffer = new MultiByteBuffer();
        for (int i = 0; i < 500; i++) {
            byte[] randBytes = new byte[1024];
            ByteBuffer byteBuffer = ByteBuffer.allocate(1024);
            byteBuffer.get(randBytes);
            byteBuffer.flip();
            multiByteBuffer.put(byteBuffer);
        }
        multiByteBuffer.mark();
        for (int i = 1; i < 500 * 1024; i++) {
            multiByteBuffer.get();
        }
        long a = System.nanoTime();
        multiByteBuffer.clear(recycleByteBufferQueue);
        System.out.println(System.nanoTime() - a);
        System.out.println(multiByteBuffer);
    }
}
