package org.microprofile.test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.microprofile.nio.SocketServer;
import org.microprofile.nio.handler.ProtocolHandler;

public class NioServerTest {

    public static void main(String[] arg) throws IOException {
        SocketServer socketServer = new SocketServer(1000, null, new NioServerProtocolHandler());
        socketServer.listen();
        socketServer.close();
    }

}

class NioServerProtocolHandler implements ProtocolHandler {

    @Override
    public void read(SelectionKey key, ByteBuffer byteBuffer) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        byteBuffer.flip();
        byte[] dst = new byte[byteBuffer.limit()];
        byteBuffer.get(dst);
        byteBuffer.clear();
        client.write(ByteBuffer.wrap(dst));
    }

    @Override
    public void close(SelectionKey key) throws IOException {

    }
}