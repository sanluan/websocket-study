package org.microprofile.test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Scanner;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.microprofile.nio.SocketClient;
import org.microprofile.nio.handler.ChannelContext;
import org.microprofile.nio.handler.ProtocolHandler;

public class NioClientTest {
    protected static final Log log = LogFactory.getLog(NioClientTest.class);

    public static void main(String[] arg) throws IOException, InterruptedException {
        SocketClient socketClient = new SocketClient("127.0.0.1", 1000, null, new NioClientProtocolHandler());
        new NioClientThread(socketClient).start();
        log.info("please input you message,quit to exit");
        Scanner in = new Scanner(System.in);
        while (true) {
            String message = in.nextLine();
            if (message.equals("quit")) {
                in.close();
                socketClient.close();
                break;
            }
            socketClient.sendMessage(message);
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
    public void read(ChannelContext<Object> channelContext, ByteBuffer byteBuffer) {
        byteBuffer.flip();
        byte[] dst = new byte[byteBuffer.limit()];
        byteBuffer.get(dst);
        byteBuffer.clear();
        log.info("recive:" + new String(dst));
    }

    @Override
    public void close(ChannelContext<Object> channelContext) {

    }
}