package com.publiccms.message;

import java.util.HashMap;
import java.util.Map;

import org.microprofile.message.ThinMessageServer;
import org.microprofile.message.initializer.ThinAppHandler;
import org.microprofile.message.initializer.ThinAppInitializer;
import org.microprofile.websocket.handler.MessageHandler;

public class ManageInitializer implements ThinAppInitializer {
    private ThinMessageServer server;
    public void start(String appPath, ThinAppHandler handler) {
        server = handler.getMessageServer();
    }

    public Map<MessageHandler, String[]> register() {
        HashMap<MessageHandler, String[]> map = new HashMap<>();
        map.put(new ManageMessageHandler(server), new String[] { "/*" });
        return map;
    }

    public void stop() {
    }
}
