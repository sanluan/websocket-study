package org.microprofile.test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.microprofile.websocket.WebSocketServer;
import org.microprofile.websocket.handler.MessageHandler;
import org.microprofile.websocket.handler.Session;

public class WebSocketServerTest {
    protected static final Log log = LogFactory.getLog(WebSocketServerTest.class);

    public static void main(String[] args) throws InterruptedException {
        try {
            WebSocketServer ws = new WebSocketServer(1000, 20, new ServerMessageHandler(), 1000);
            log.info("启动。。。");
            ws.asyncListen();
            Thread.sleep(1000 * 1000);
            ws.close();
        } catch (IOException e) {
        }
    }

}

class ServerMessageHandler implements MessageHandler {
    protected final Log log = LogFactory.getLog(getClass());
    private List<Session> sessionList = new ArrayList<Session>();
    int last = 0;
    long start = 0;
    int n = 0;

    @Override
    public void onMessage(byte[] message, Session session) throws IOException {
        int r = message.length;
        for (int i = 0; i < r; i++) {
            if (last != message[i]) {
                last = message[i];
                System.out.println("error");
            }
            last++;
            if (last == 125) {
                last = 0;
                n++;
                if (n % 100000 == 0) {
                    System.out.println(n);
                    if (1000000 == n) {
                        System.out.println(System.currentTimeMillis() - start);
                    }
                }
            }
        }
    }

    @Override
    public void onMessage(String message, Session session) throws IOException {
        log.info(message);
        session.sendString(message);
    }

    @Override
    public void onOpen(Session session) throws IOException {
        sessionList.add(session);
        log.info(session.getId() + "\t connected!");
        start = System.currentTimeMillis();
    }

    @Override
    public void onClose(Session session) throws IOException {
        sessionList.remove(session);
        log.info(session.getId() + "\t closed!");
    }

}
