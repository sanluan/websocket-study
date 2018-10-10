package org.microprofile.websocket.handler;

import java.nio.ByteBuffer;

public class WebSocketFrame {
    private Session session;
    private ByteBuffer lastMessage;
    private boolean initialized;

    public Session getSession() {
        return session;
    }

    public void setSession(Session session) {
        this.session = session;
    }

    public ByteBuffer getLastMessage() {
        return lastMessage;
    }

    public void setLastMessage(ByteBuffer lastMessage) {
        this.lastMessage = lastMessage;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void setInitialized(boolean initialized) {
        this.initialized = initialized;
    }
}
