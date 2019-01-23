package org.microprofile.message.handler;

import java.io.IOException;

import org.microprofile.common.constant.Constants;
import org.microprofile.websocket.handler.MessageHandler;
import org.microprofile.websocket.handler.Session;

public class DefaultMessageHandler implements MessageHandler {

    @Override
    public void onMessage(byte[] message, Session session) throws IOException {
        onMessage(new String(message, Constants.DEFAULT_CHARSET), session);
    }

    @Override
    public void onMessage(String message, Session session) throws IOException {
        session.sendString("receive:" + message);
    }

    @Override
    public void onOpen(Session session) throws IOException {
        session.sendString("welcome");
    }

    @Override
    public void onClose(Session session) throws IOException {
        session.sendString("bye");
    }

}
