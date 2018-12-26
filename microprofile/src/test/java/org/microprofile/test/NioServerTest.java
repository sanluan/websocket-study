package org.microprofile.test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

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

    @Override
    public void read(ChannelContext<Object> channelContext, ByteBuffer byteBuffer) throws IOException {
        SocketChannel client = channelContext.getSocketChannel();
        byteBuffer.flip();
        byte[] dst = new byte[byteBuffer.limit()];
        byteBuffer.get(dst);
        byteBuffer.clear();
        client.write(ByteBuffer.wrap(dst));
    }

    @Override
    public void close(ChannelContext<Object> channelContext) throws IOException {

    }
}