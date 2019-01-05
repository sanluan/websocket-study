package org.microprofile.websocket.handler;

import java.io.IOException;

public interface MessageHandler {
    public void onMessage(byte[] message, Session session) throws IOException;

    public void onMessage(String message, Session session) throws IOException;

    public void onOpen(Session session) throws IOException;

    public void onClose(Session session) throws IOException;
}