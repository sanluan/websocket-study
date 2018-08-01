package org.microprofile.test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.microprofile.websocket.WebSocketServer;
import org.microprofile.websocket.handler.Message;
import org.microprofile.websocket.handler.MessageHandler;
import org.microprofile.websocket.handler.Session;

public class WebSocketServerTest {

    public static void main(String[] args) throws InterruptedException {
        try {
            WebSocketServer ws = new WebSocketServer(1000, 1, new ServerMessageHandler());
            System.out.println("启动。。。");
            ws.listen();
            ws.close();
        } catch (IOException e) {
        }
    }

}

class ServerMessageHandler implements MessageHandler {
    private List<Session> sessionList = new ArrayList<Session>();

    @Override
    public void onMessage(Message message, Session session) throws IOException {
        String str = new String(message.getPayload());
        System.out.println(str);
        if (str.startsWith("群发:")) {
            for (Session s : sessionList) {
                s.sendString(str);
            }
        }
    }

    @Override
    public void onOpen(Session session) throws IOException {
        sessionList.add(session);
    }

    @Override
    public void onClose(Session session) throws IOException {
        sessionList.remove(session);
    }

}
