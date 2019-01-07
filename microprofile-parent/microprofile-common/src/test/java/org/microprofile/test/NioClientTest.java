package org.microprofile.test;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.microprofile.common.buffer.MultiByteBuffer;
import org.microprofile.nio.SocketClient;
import org.microprofile.nio.handler.ChannelContext;
import org.microprofile.nio.handler.ProtocolHandler;

public class NioClientTest {
    protected static final Log log = LogFactory.getLog(NioClientTest.class);

    public static void main(String[] arg) throws InterruptedException, IOException {
        SocketClient socketClient = new SocketClient("127.0.0.1", 1000, null, new NioClientProtocolHandler());
        socketClient.asyncListen();
        while (!socketClient.isOpen()) {
            Thread.sleep(100);
        }
        for (int i = 0; i < 10000; i++) {
            byte[] randBytes = new byte[1000000];
            for (int j = 0; j < 1000000; j++) {
                randBytes[j] = (byte) (j % 126);
            }
            socketClient.sendMessage(randBytes);
        }
        Thread.sleep(5000);
        socketClient.close();
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