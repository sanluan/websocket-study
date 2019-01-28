package org.microprofile.test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.microprofile.websocket.WebSocketClient;
import org.microprofile.websocket.handler.MessageHandler;
import org.microprofile.websocket.handler.Session;

public class WebSocketClientTest {
    protected static final Log log = LogFactory.getLog(WebSocketClientTest.class);

    public static void main(String[] args) throws InterruptedException, URISyntaxException {
        try {
            WebSocketClient ws = new WebSocketClient("wss://cms.publiccms.com/message/test/", new ClientMessageHandler());
            log.info("启动。。。");
            ws.asyncListen();
            while (!ws.isOpen()) {
                Thread.sleep(100);
            }
            ws.sendString("u:");
            Thread.sleep(10000);
            ws.close();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        } catch (KeyManagementException e) {
            e.printStackTrace();
        }
    }

}

class ClientMessageHandler implements MessageHandler {
    protected final Log log = LogFactory.getLog(getClass());

    @Override
    public void onMessage(byte[] message, Session session) throws IOException {
        String str = new String(message);
        log.info("recive:" + str);
    }

    @Override
    public void onMessage(String message, Session session) throws IOException {
        log.info("recive:" + message);
    }

    @Override
    public void onOpen(Session session) throws IOException {
        log.info(session.getId() + "\t connected!");
    }

    @Override
    public void onClose(Session session) throws IOException {
        log.info(session.getId() + "\t closed!");
    }

}
