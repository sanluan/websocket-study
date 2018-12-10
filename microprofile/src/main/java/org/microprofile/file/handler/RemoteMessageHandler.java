package org.microprofile.file.handler;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.microprofile.file.constant.Constants;
import org.microprofile.file.event.EventHandler;
import org.microprofile.websocket.handler.MessageHandler;
import org.microprofile.websocket.handler.Session;

public class RemoteMessageHandler implements MessageHandler {
    protected final Log log = LogFactory.getLog(getClass());

    private EventHandler eventHandler;

    /**
     * @param eventHandler
     */
    public RemoteMessageHandler(EventHandler eventHandler) {
        super();
        this.eventHandler = eventHandler;
    }

    @Override
    public void onMessage(String message, Session session) throws IOException {
        if (0 < message.length()) {
            this.eventHandler.handle(message.getBytes(Constants.DEFAULT_CHARSET), session);
        }
    }

    @Override
    public void onMessage(byte[] message, Session session) throws IOException {
        if (0 < message.length) {
            this.eventHandler.handle(message, session);
        }
    }

    @Override
    public void onOpen(Session session) throws IOException {
        this.eventHandler.register(session);
    }

    @Override
    public void onClose(Session session) throws IOException {
        this.eventHandler.closing(session);
    }
}
