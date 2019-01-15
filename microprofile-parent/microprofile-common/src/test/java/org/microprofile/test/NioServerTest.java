package org.microprofile.test;

import java.io.IOException;
import java.util.concurrent.Executors;

import org.microprofile.common.buffer.MultiByteBuffer;
import org.microprofile.nio.SocketServer;
import org.microprofile.nio.handler.ChannelContext;
import org.microprofile.nio.handler.ProtocolHandler;

public class NioServerTest {

    public static void main(String[] arg) throws IOException {
        SocketServer socketServer = new SocketServer(1000, Executors.newFixedThreadPool(20), new NioServerProtocolHandler(),
                100 * 1024);
        socketServer.listen();
        socketServer.close();
    }
}

class NioServerProtocolHandler implements ProtocolHandler<Object> {
    int last = 0;
    int n = 0;
    long start = 0;

    @Override
    public void read(ChannelContext<Object> channelContext, MultiByteBuffer byteBuffer) throws IOException {
        if (0 == start) {
            start = System.currentTimeMillis();
        }
        int r = byteBuffer.remaining();
        byte[] dst = new byte[r];
        byteBuffer.get(dst);
        for (int i = 0; i < r; i++) {
            if (last != dst[i]) {
                last = dst[i];
                System.out.println("error");
            }
            last++;
            if (last == 125) {
                last = 0;
                n++;
                if (n % 100000 == 0) {
                    System.out.println(n);
                    if (1000000 == n) {
                        System.out.println(System.currentTimeMillis() - start);
                    }
                }
            }
        }
    }

    @Override
    public void close(ChannelContext<Object> channelContext) throws IOException {

    }
}