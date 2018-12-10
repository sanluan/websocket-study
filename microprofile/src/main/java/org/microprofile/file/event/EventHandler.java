package org.microprofile.file.event;

import org.microprofile.websocket.handler.Session;

public interface EventHandler {
    public void handle(FileEvent event);

    public void handle(byte[] payload, Session session);

    public void register(Session session);

    public void closing(Session session);

    public void cache(FileEvent event);
}