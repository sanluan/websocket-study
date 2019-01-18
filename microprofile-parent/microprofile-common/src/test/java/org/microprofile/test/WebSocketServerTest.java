package org.microprofile.test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

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
    Map<Session, State> map = new HashMap<>();

    public class State {
        int last = 0;
        int n = 0;
        long start = 0;
    }

    @Override
    public void onMessage(byte[] message, Session session) throws IOException {
        State state = map.get(session);
        int r = message.length;
        for (int i = 0; i < r; i++) {
            if (state.last != message[i]) {
                state.last = message[i];
                System.out.println("error");
            }
            state.last++;
            if (state.last == 125) {
                state.last = 0;
                state.n++;
                if (state.n % 100000 == 0) {
                    System.out.println(state.n);
                    if (1000000 == state.n) {
                        System.out.println(System.currentTimeMillis() - state.start);
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
        log.info(session.getId() + "\t connected!");
        State state = new State();
        state.start = System.currentTimeMillis();
        map.put(session, state);
    }

    @Override
    public void onClose(Session session) throws IOException {
        map.remove(session);
        log.info(session.getId() + "\t closed!");
    }

}
