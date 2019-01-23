package org.microprofile.message.initializer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.microprofile.message.ThinMessageServer;
import org.microprofile.websocket.handler.MessageHandler;

public abstract class ThinAppHandler implements MessageHandler{
    protected String appPath;
    protected ThinMessageServer messageServer;
    protected final Log log = LogFactory.getLog(getClass());

    public String getAppPath() {
        return appPath;
    }

    public ThinAppHandler setAppPath(String appPath) {
        this.appPath = appPath;
        return this;
    }

    public ThinMessageServer getHttpServer() {
        return messageServer;
    }

    public ThinAppHandler setMessageServer(ThinMessageServer messageServer) {
        this.messageServer = messageServer;
        return this;
    }

    abstract public void init();

    abstract public void shutdown();
}