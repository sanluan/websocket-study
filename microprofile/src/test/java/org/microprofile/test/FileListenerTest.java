package org.microprofile.test;

import org.microprofile.file.handler.FileEventHandler;
import org.microprofile.file.listener.FileListener;
import org.microprofile.websocket.WebSocketClient;
import org.microprofile.websocket.WebSocketServer;

public class FileListenerTest {

    public static void main(String[] args) {
        try {

            FileListener listener1 = new FileListener("D:/aaa/");
            FileEventHandler fileEventHandler1 = new FileEventHandler(listener1.getLocalFileHandler());
            WebSocketServer wss = new WebSocketServer(1000, 1, fileEventHandler1);
            wss.asyncListen();
            listener1.addEventHandler(fileEventHandler1);
            listener1.start();

            FileListener listener = new FileListener("D:/bbb/");
            FileEventHandler fileEventHandler = new FileEventHandler(listener.getLocalFileHandler());
            WebSocketClient ws = new WebSocketClient("localhost", 1000, fileEventHandler);

            listener.addEventHandler(fileEventHandler);
            listener.start();
            ws.asyncListen();
            Thread.sleep(1000 * 1000);
            wss.close();
            ws.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}