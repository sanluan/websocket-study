package org.microprofile.test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Scanner;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.microprofile.common.buffer.MultiByteBuffer;
import org.microprofile.nio.SocketClient;
import org.microprofile.nio.handler.ChannelContext;
import org.microprofile.nio.handler.ProtocolHandler;

public class NioClientTest {
    protected static final Log log = LogFactory.getLog(NioClientTest.class);

    public static void main(String[] arg) throws IOException, InterruptedException {
        SocketClient socketClient = new SocketClient("127.0.0.1", 1000, null, new NioClientProtocolHandler());
        new NioClientThread(socketClient).start();
        log.info("please input you message,quit to exit");
        for (int i = 0; i < 100; i++) {
            byte[] randBytes = new byte[1000000];
            for (int j = 0; j < 1000000; j++) {
                randBytes[j] = (byte) (j % 126);
            }
            socketClient.sendMessage(randBytes);
        }
    }

}

class NioClientThread extends Thread {
    private SocketClient socketClient;

    public NioClientThread(SocketClient socketClient) {
        this.socketClient = socketClient;
    }

    public void run() {
        try {
            socketClient.listen();
        } catch (IOException e) {
        }
    }
}

class NioClientProtocolHandler implements ProtocolHandler<Object> {
    protected final Log log = LogFactory.getLog(getClass());

    @Override
    public void read(ChannelContext<Object> channelContext, MultiByteBuffer byteBuffer) {
        byte[] dst = new byte[byteBuffer.limit()];
        byteBuffer.get(dst);
        log.info("recive:" + new String(dst));
    }

    @Override
    public void close(ChannelContext<Object> channelContext) {

    }
}