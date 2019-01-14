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
    int count = 0;
    int n = 0;

    @Override
    public void onMessage(byte[] message, Session session) throws IOException {
        for (int i = 0; i < message.length; i++) {
            if (last != message[i]) {
                last = 0;
                System.out.println("error");
            }
            last++;
            count++;
            if (last == 126) {
                last = 0;
            }
        }
        last = 0;
        count = 0;
        n++;
        System.out.println(session.getId() + "\t" + n + "\t" + System.currentTimeMillis());
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
    }

    @Override
    public void onClose(Session session) throws IOException {
        sessionList.remove(session);
        log.info(session.getId() + "\t closed!");
    }

}
