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
            WebSocketServer ws = new WebSocketServer(1000, 1, new ServerMessageHandler());
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

    @Override
    public void onMessage(byte[] message, Session session) throws IOException {
        String str = new String(message);
        log.info(str);
        session.sendByte(message);
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
