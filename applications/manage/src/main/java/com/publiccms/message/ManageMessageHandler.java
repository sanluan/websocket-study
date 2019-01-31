package com.publiccms.message;

import java.io.IOException;
import java.util.UUID;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.microprofile.common.constant.Constants;
import org.microprofile.message.ThinMessageServer;
import org.microprofile.websocket.handler.MessageHandler;
import org.microprofile.websocket.handler.Session;

public class ManageMessageHandler implements MessageHandler {
    protected final Log log = LogFactory.getLog(getClass());
    private ThinMessageServer server;
    private Session adminSession;
    private String password;

    /**
     * @param server
     * 
     */
    public ManageMessageHandler(ThinMessageServer server) {
        this.password = UUID.randomUUID().toString();
        this.server = server;
        log.warn("applications manage password:" + password);
    }

    public void onMessage(byte[] message, Session session) throws IOException {
        onMessage(new String(message, Constants.DEFAULT_CHARSET), session);
    }

    public void onMessage(String message, Session session) throws IOException {
        if (1 < message.length() && ':' == message.charAt(1)) {
            char command = message.charAt(0);
            message = message.substring(2);
            switch (command) {
            case 'a': {
                if (password.equals(message)) {
                    adminSession = session;
                    session.sendString("s:login success!");
                }
                break;
            }
            case 'l': {
                if (null != adminSession && adminSession == session) {
                    String[] paths = message.split(",");
                    if (1 == paths.length) {
                        server.load(paths[0]);
                    } else if (2 == paths.length) {
                        server.load(paths[0], paths[1]);
                    } else {
                        session.sendString("s:error path");
                    }
                } else {
                    session.sendString("s:no auth");
                }
                break;
            }
            case 'd': {
                if (null != adminSession && adminSession == session) {
                    String[] paths = message.split(",");
                    if (1 == paths.length) {
                        server.unLoad(paths[0]);
                    } else {
                        session.sendString("s:error path");
                    }
                } else {
                    session.sendString("s:no auth");
                }
                break;
            }
            case 's': {
                if (null != adminSession && adminSession == session) {
                    server.stop();
                } else {
                    session.sendString("s:no auth");
                }
                break;
            }
            case 'r': {
                String[] paths = message.split(",");
                if (1 == paths.length) {
                    server.unLoad(paths[0]);
                    server.load(paths[0]);
                } else if (2 == paths.length) {
                    server.unLoad(paths[0]);
                    server.load(paths[0], paths[1]);
                } else {
                    session.sendString("s:error path");
                }
                break;
            }
            default:
                session.sendString("s:error message");
            }
        }
    }

    public void onOpen(Session session) throws IOException {
        log.info(session.getId() + "\t connected!");
    }

    public void onClose(Session session) throws IOException {
        log.info(session.getId() + "\t closed!");
        if (null != adminSession && adminSession == session) {
            adminSession = null;
        }
    }
}
