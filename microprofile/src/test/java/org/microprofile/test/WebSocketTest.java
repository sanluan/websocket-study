package org.microprofile.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.Vector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.microprofile.websocket.WebSocketClient;
import org.microprofile.websocket.WebSocketServer;
import org.microprofile.websocket.handler.MessageHandler;
import org.microprofile.websocket.handler.Session;

public class WebSocketTest {

    @Test
    @DisplayName("测试websocket连接")
    public void testWebsocket() throws IOException, InterruptedException {

        WebSocketTestHandler serverHandler = new WebSocketTestHandler();
        WebSocketServer wss = new WebSocketServer(1000, 1, serverHandler);
        wss.asyncListen();
        WebSocketTestHandler clientHandler = new WebSocketTestHandler();
        WebSocketClient wsc = new WebSocketClient("localhost", 1000, clientHandler);
        wsc.asyncListen();
        Thread.sleep(100);
        assertEquals(1, serverHandler.sessions.size());
        assertEquals(1, clientHandler.sessions.size());
        Random random = new Random();
        byte[] randBytes = new byte[64];
        random.nextBytes(randBytes);
        wsc.sendByte(randBytes);
        Thread.sleep(100);
        assertEquals(serverHandler.receivedMessageList.get(0), randBytes);
        clientHandler.receivedMessageList.remove(0);
        wss.close();
        wsc.close();
    }
}

class WebSocketTestHandler implements MessageHandler {
    Set<Session> sessions = new HashSet<>();
    Vector<byte[]> receivedMessageList = new Vector<>();

    public WebSocketTestHandler() {

    }

    @Override
    public void onMessage(String message, Session session) throws IOException {
        receivedMessageList.add(message.getBytes());
    }

    @Override
    public void onMessage(byte[] message, Session session) throws IOException {
        receivedMessageList.add(message);
    }

    @Override
    public void onOpen(Session session) throws IOException {
        this.sessions.add(session);
    }

    @Override
    public void onClose(Session session) throws IOException {
        this.sessions.remove(session);
    }
}
