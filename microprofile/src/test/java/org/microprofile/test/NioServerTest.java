package org.microprofile.test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import org.microprofile.common.buffer.MultiByteBuffer;
import org.microprofile.nio.SocketServer;
import org.microprofile.nio.handler.ChannelContext;
import org.microprofile.nio.handler.ProtocolHandler;

public class NioServerTest {

    public static void main(String[] arg) throws IOException {
        SocketServer socketServer = new SocketServer(1000, null, new NioServerProtocolHandler());
        socketServer.listen();
        socketServer.close();
    }
}

class NioServerProtocolHandler implements ProtocolHandler<Object> {
    int last = 0;
    int count = 0;

    @Override
    public void read(ChannelContext<Object> channelContext, MultiByteBuffer byteBuffer) throws IOException {
        byte[] dst = new byte[byteBuffer.limit()];
        byteBuffer.get(dst);
        for (int i = 0; i < byteBuffer.limit(); i++) {
            if (last != dst[i]) {
                System.out.println(1);
            }
            last++;
            count++;
            if (last == 126) {
                last = 0;
            }
            if (count == 1000000) {
                last = 0;
            }
        }
    }

    @Override
    public void close(ChannelContext<Object> channelContext) throws IOException {

    }
}