package org.microprofile.message.initializer;

import java.util.Map;

import org.microprofile.websocket.handler.MessageHandler;

public interface ThinAppInitializer {
    public void start(String appPath, ThinAppHandler handler);

    public Map<MessageHandler, String[]> register();

    public void stop();
}