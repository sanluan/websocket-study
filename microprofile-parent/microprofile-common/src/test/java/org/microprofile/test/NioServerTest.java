package org.microprofile.test;

import java.io.IOException;
import java.util.concurrent.Executors;

import org.microprofile.common.buffer.MultiByteBuffer;
import org.microprofile.nio.SocketServer;
import org.microprofile.nio.handler.ChannelContext;
import org.microprofile.nio.handler.ProtocolHandler;

public class NioServerTest {

    public static void main(String[] arg) throws IOException {
        SocketServer socketServer = new SocketServer(1000, Executors.newFixedThreadPool(20), new NioServerProtocolHandler());
        socketServer.listen();
        socketServer.close();
    }
}

class NioServerProtocolHandler implements ProtocolHandler<Object> {
    int last = 0;
    int count = 0;
    int n = 0;

    @Override
    public void read(ChannelContext<Object> channelContext, MultiByteBuffer byteBuffer) throws IOException {
        int r = byteBuffer.remaining();
        byte[] dst = new byte[r];
        byteBuffer.get(dst);
        for (int i = 0; i < r; i++) {
            if (last != dst[i]) {
                last = dst[i];
                System.out.println("error");
            }
            last++;
            count++;
            if (last == 126) {
                last = 0;
            }
            if (count == 1000000) {
                last = 0;
                count = 0;
                n++;
                System.out.println(n);
            }
        }
    }

    @Override
    public void close(ChannelContext<Object> channelContext) throws IOException {

    }
}