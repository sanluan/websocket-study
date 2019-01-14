package org.microprofile.test;

import java.io.IOException;
import java.net.URISyntaxException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.microprofile.websocket.WebSocketClient;
import org.microprofile.websocket.handler.MessageHandler;
import org.microprofile.websocket.handler.Session;

public class WebSocketClientTest {
    protected static final Log log = LogFactory.getLog(WebSocketClientTest.class);

    public static void main(String[] args) throws InterruptedException, URISyntaxException {
        try {
            WebSocketClient ws = new WebSocketClient("ws://localhost:1000", new ClientMessageHandler());
            log.info("启动。。。");
            ws.asyncListen();
            while (!ws.isOpen()) {
                Thread.sleep(100);
            }
            for (int i = 0; i < 1000000; i++) {
                byte[] randBytes = new byte[125];
                for (int j = 0; j < 125; j++) {
                    randBytes[j] = (byte) (j % 126);
                }
                ws.sendByte(randBytes);
            }
            ws.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}

class ClientMessageHandler implements MessageHandler {
    protected final Log log = LogFactory.getLog(getClass());

    @Override
    public void onMessage(byte[] message, Session session) throws IOException {
        String str = new String(message);
        log.info("recive:" + str);
    }

    @Override
    public void onMessage(String message, Session session) throws IOException {
        log.info("recive:" + message);
    }

    @Override
    public void onOpen(Session session) throws IOException {
    }

    @Override
    public void onClose(Session session) throws IOException {
    }

}
