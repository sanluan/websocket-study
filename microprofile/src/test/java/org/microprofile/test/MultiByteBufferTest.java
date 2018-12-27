package org.microprofile.test;

import java.nio.ByteBuffer;
import java.util.Random;

import org.microprofile.common.buffer.MultiByteBuffer;

public class MultiByteBufferTest {
    public static void main(String[] args) {
        Random random = new Random();
        MultiByteBuffer multiByteBuffer = new MultiByteBuffer();
        for (int i = 0; i < 5; i++) {
            int start = random.nextInt(1000);
            byte[] randBytes = new byte[1 + random.nextInt(1023 - start)];
            ByteBuffer byteBuffer = ByteBuffer.allocate(1024);
            byteBuffer.position(start);
            byteBuffer.get(randBytes);
            byteBuffer.flip();
            for (int j = 0; j < start; j++) {
                byteBuffer.get();
            }
            System.out.println(byteBuffer);
            multiByteBuffer.put(byteBuffer);
        }
        int remaining = multiByteBuffer.remaining();
        System.out.println(remaining);
        for (int i = 0; i <= remaining; i++) {
            multiByteBuffer.position(i);
        }
        System.out.println(multiByteBuffer);

        for (int i = remaining; i >= 0; i--) {
            multiByteBuffer.position(i);
        }
        System.out.println(multiByteBuffer);
    }
}
