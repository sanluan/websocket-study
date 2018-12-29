package org.microprofile.test;

import java.io.IOException;
import java.util.Scanner;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.microprofile.websocket.WebSocketClient;
import org.microprofile.websocket.handler.MessageHandler;
import org.microprofile.websocket.handler.Session;

public class WebSocketClientTest {
    protected static final Log log = LogFactory.getLog(WebSocketClientTest.class);

    public static void main(String[] args) throws InterruptedException {
        try {
            WebSocketClient ws = new WebSocketClient("localhost", 1000, "/", new ClientMessageHandler());
            log.info("启动。。。");
            new WebSocketClientThread(ws).start();
            log.info("please input you message,quit to exit");
            Scanner in = new Scanner(System.in);
            while (true) {
                String message = in.nextLine();
                if (message.equals("quit")) {
                    in.close();
                    ws.close();
                    break;
                }
                ws.sendString(message);
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
