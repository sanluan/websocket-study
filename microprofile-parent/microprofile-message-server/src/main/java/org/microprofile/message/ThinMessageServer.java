package org.microprofile.message;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.microprofile.message.initializer.DefaultAppHandler;
import org.microprofile.message.initializer.ThinAppHandler;
import org.microprofile.websocket.WebSocketServer;
import org.microprofile.websocket.handler.MessageHandler;
import org.microprofile.websocket.handler.Session;

public class ThinMessageServer implements MessageHandler {
    protected final Log log = LogFactory.getLog(getClass());
    public final static String SERVER_ROOT_PATH = "apps";
    public final static String WEBAPP_ROOT_PATH = "ROOT";
    private Map<String, ThinAppHandler> handlerMap = new HashMap<>();
    private ThinAppHandler defaultHandler;
    private WebSocketServer ws;
    private int port;

    public ThinMessageServer(int port, int poolSize, int maxPending) {
        try {
            this.port = port;
            ws = new WebSocketServer(port, poolSize, this, 1000);
            log.info("启动。。。");
            File file = new File(SERVER_ROOT_PATH);
            if (file.isDirectory()) {
                for (File app : file.listFiles()) {
                    if (app.isDirectory()) {
                        load(app.getName());
                    }
                }
            } else {
                file.mkdirs();
            }
            if (null == defaultHandler) {
                defaultHandler = new DefaultAppHandler();
                defaultHandler.setContextPath("/");
                defaultHandler.setAppPath(SERVER_ROOT_PATH + "/" + WEBAPP_ROOT_PATH);
                defaultHandler.setMessageServer(this);
                defaultHandler.init();
            }
            ws.asyncListen();
            log.info("listened on " + port);
        } catch (IOException e) {
            try {
                if (null != ws && ws.isOpen()) {
                    stop();
                }
            } catch (IOException e1) {
            }
            log.error(e.getMessage());
        }
    }

    public static void main(String[] args) {
        new ThinMessageServer(Integer.getInteger("thinMessageServer.port", 1000).intValue(),
                Integer.getInteger("thinMessageServer.poolSize", 20).intValue(),
                Integer.getInteger("thinMessageServer.maxPending", 1000).intValue());
    }

    public void stop() throws IOException {
        for (ThinAppHandler handler : handlerMap.values()) {
            handler.shutdown();
        }
        ws.close();
    }

    public void unLoad(String path) {
        ThinAppHandler handler = handlerMap.remove(path);
        if (null != handler) {
            handler.shutdown();
        }
    }

    public void load(String path) {
        load(path, null);
    }

    public void load(String contextPath, String appPath) {
        if (WEBAPP_ROOT_PATH.equals(contextPath)) {
            contextPath = "/";
        } else {
            contextPath = "/" + contextPath;
        }
        log.info("[" + contextPath + "] initialize start!");
        ThinAppHandler handler = new DefaultAppHandler();
        if (null == appPath) {
            appPath = SERVER_ROOT_PATH + "/" + contextPath;
        }
        File file = new File(appPath);
        if (file.exists() && file.isDirectory()) {
            handler.setContextPath(contextPath);
            handler.setAppPath(appPath);
            handler.setMessageServer(this);
            handler.init();

            if ("/".equals(contextPath)) {
                defaultHandler = handler;
            }
            handlerMap.put(contextPath, handler);
            log.info("[" + contextPath + "] initialize complete!");
        } else {
            log.info("[" + contextPath + "] not exists! path:" + appPath);
        }
    }

    private ThinAppHandler getHandler(Session session) {
        String contextPath = session.getContextPath();
        if (null == contextPath) {
            String url = session.getUrl();
            if (null != url && url.startsWith("/")) {
                int index = url.indexOf("/", 1);
                if (0 < index) {
                    contextPath = url.substring(0, index);
                    session.setUrl(url.substring(index));
                } else {
                    contextPath = "/";
                }
            } else {
                contextPath = "/";
            }
            session.setContextPath(contextPath);
        }
        ThinAppHandler handler = handlerMap.get(contextPath);
        if (null == handler && "/".equals(contextPath)) {
            handler = defaultHandler;
        }
        return handler;
    }

    @Override
    public void onMessage(byte[] message, Session session) throws IOException {
        ThinAppHandler handler = getHandler(session);
        if (null != handler) {
            handler.onMessage(message, session);
        }
    }

    @Override
    public void onMessage(String message, Session session) throws IOException {
        ThinAppHandler handler = getHandler(session);
        if (null != handler) {
            handler.onMessage(message, session);
        }
    }

    @Override
    public void onOpen(Session session) throws IOException {
        ThinAppHandler handler = getHandler(session);
        if (null != handler) {
            handler.onOpen(session);
        }
    }

    @Override
    public void onClose(Session session) throws IOException {
        ThinAppHandler handler = getHandler(session);
        if (null != handler) {
            handler.onClose(session);
        }
    }

    /**
     * @return the port
     */
    public int getPort() {
        return port;
    }
}
