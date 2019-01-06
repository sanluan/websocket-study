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
            new WebSocketClientThread(ws).start();
            Thread.sleep(1000);
            for (int i = 0; i < 1000; i++) {
                byte[] randBytes = new byte[1000000];
                for (int j = 0; j < 1000000; j++) {
                    randBytes[j] = (byte) (j % 126);
                }
                ws.sendByte(randBytes);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}

class WebSocketClientThread extends Thread {
    private WebSocketClient ws;

    public WebSocketClientThread(WebSocketClient ws) {
        this.ws = ws;
    }

    public void run() {
        try {
            ws.listen();
        } catch (IOException e) {
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
