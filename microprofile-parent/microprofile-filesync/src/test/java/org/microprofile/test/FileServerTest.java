package org.microprofile.test;

import org.microprofile.file.handler.FileEventHandler;
import org.microprofile.file.listener.FileListener;
import org.microprofile.websocket.WebSocketServer;

public class FileServerTest {

    public static void main(String[] args) {
        try {

            FileListener listener = new FileListener("D:/aaa/");
            FileEventHandler fileEventHandler = new FileEventHandler(listener.getLocalFileAdaptor());
            WebSocketServer wss = new WebSocketServer(1000, 1, fileEventHandler.getRemoteMessageHandler(true), 1000);
            wss.asyncListen();
            listener.addEventHandler(fileEventHandler);
            listener.start();

            Thread.sleep(1000 * 1000);
            listener.stop();
            wss.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}