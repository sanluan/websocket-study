package org.microprofile.test;

import java.nio.ByteBuffer;

import org.microprofile.common.buffer.MultiByteBuffer;

public class MultiByteBufferTest {
    public static void main(String[] args) {
        MultiByteBuffer multiByteBuffer = new MultiByteBuffer();
        for (int i = 0; i < 500; i++) {
            byte[] randBytes = new byte[1024];
            ByteBuffer byteBuffer = ByteBuffer.allocate(1024);
            byteBuffer.get(randBytes);
            byteBuffer.flip();
            multiByteBuffer.put(byteBuffer);
        }
        int remaining = multiByteBuffer.remaining();
        long n = System.nanoTime();
        for (int i = 0; i < remaining; i++) {
            multiByteBuffer.get();
        }
        System.out.println(System.nanoTime() - n);
        byte[] randBytes = new byte[1024 * 500];
        ByteBuffer byteBuffer = ByteBuffer.allocate(1024 * 500);
        byteBuffer.get(randBytes);
        byteBuffer.flip();
        int remaining1 = byteBuffer.remaining();
        long a = System.nanoTime();
        for (int i = 0; i < remaining1; i++) {
            byteBuffer.get();
        }
        System.out.println(System.nanoTime() - a);
    }
}
