package org.microprofile.message.initializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.microprofile.message.handler.DefaultMessageHandler;
import org.microprofile.websocket.handler.MessageHandler;
import org.microprofile.websocket.handler.Session;

public class DefaultAppHandler extends ThinAppHandler {
    private ThinAppClassLoader appClassLoader;
    private ThinAppHandler customHandler;
    public final static String WEBAPP_INFO_PATH = "/WEB-INF";
    List<String> cachedUrl = new ArrayList<String>();
    Map<String, MessageHandler> cachedMappings = new LinkedHashMap<>();
    Map<String, MessageHandler> urlMappings = new LinkedHashMap<>();
    Map<String, MessageHandler> dirMappings = new LinkedHashMap<>();
    Map<String, MessageHandler> fileTypeMappings = new LinkedHashMap<>();
    private MessageHandler defaultHandler;

    public void init() {
        appClassLoader = new ThinAppClassLoader(appPath + WEBAPP_INFO_PATH);
        Class<ThinAppHandler> handlerClass = appClassLoader.getHandler();
        if (null != handlerClass) {
            try {
                customHandler = handlerClass.newInstance().setAppPath(appPath);
                customHandler.init();
            } catch (InstantiationException e) {
                log.error(e.getMessage());
            } catch (IllegalAccessException e) {
                log.error(e.getMessage());
            }
        }
        Map<MessageHandler, String[]> handlerMappings = new LinkedHashMap<MessageHandler, String[]>();
        for (ThinAppInitializer initializer : appClassLoader.getInitializerList()) {
            initializer.start(appPath, null == customHandler ? this : customHandler);
            handlerMappings.putAll(initializer.register());
        }
        for (Entry<MessageHandler, String[]> entry : handlerMappings.entrySet()) {
            if (null != entry.getValue()) {
                for (String path : entry.getValue()) {
                    MessageHandler handler = entry.getKey();
                    if (null == defaultHandler && "/".equals(path)) {
                        defaultHandler = handler;
                    } else {
                        if (path.startsWith("/") && path.endsWith("/*")) {
                            dirMappings.put(path.substring(0, path.length() - 1), handler);
                        } else if (path.startsWith("*.")) {
                            fileTypeMappings.put(path.substring(1), handler);
                        } else {
                            urlMappings.put(path, handler);
                        }
                    }
                    log.info(path + " mapping to " + handler.getClass().getName());
                }
            }
        }
        if (null == defaultHandler) {
            defaultHandler = new DefaultMessageHandler();
        }
    }

    @Override
    public void shutdown() {
        for (ThinAppInitializer initializer : appClassLoader.getInitializerList()) {
            initializer.stop();
        }
        if (null != customHandler) {
            customHandler.shutdown();
        }
        appClassLoader = null;
    }

    protected void cache(String path, MessageHandler handler) {
        while (50 <= cachedUrl.size()) {
            cachedMappings.remove(cachedUrl.remove(0));
        }
        cachedMappings.put(path, handler);
        cachedUrl.add(path);
    }

    protected MessageHandler getHandler(String path) {
        MessageHandler handler = cachedMappings.get(path);
        if (null == handler) {
            handler = urlMappings.get(path);
        }
        int sindex;
        String temp;
        if (null == handler && -1 < (sindex = path.lastIndexOf("/"))) {
            handler = dirMappings.get((temp = path.substring(0, sindex + 1)));
            while (null == handler && -1 < (sindex = temp.lastIndexOf("/", temp.length() - 2))) {
                handler = dirMappings.get((temp = temp.substring(0, sindex + 1)));
            }
        }
        int pindex;
        if (null == handler && 0 < (pindex = path.lastIndexOf("."))) {
            handler = fileTypeMappings.get(path.substring(pindex, path.length()));
        }
        if (null == handler) {
            handler = defaultHandler;
        }
        cache(path, handler);
        return handler;
    }

    @Override
    public void onMessage(byte[] message, Session session) throws IOException {
        getHandler(session.getUrl()).onMessage(message, session);
    }

    @Override
    public void onMessage(String message, Session session) throws IOException {
        getHandler(session.getUrl()).onMessage(message, session);
    }

    @Override
    public void onOpen(Session session) throws IOException {
        getHandler(session.getUrl()).onOpen(session);
    }

    @Override
    public void onClose(Session session) throws IOException {
        getHandler(session.getUrl()).onClose(session);
    }
}