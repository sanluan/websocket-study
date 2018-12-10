package org.microprofile.test;

import org.microprofile.file.handler.FileEventHandler;
import org.microprofile.file.handler.LocalFileAdaptor;
import org.microprofile.file.handler.RemoteMessageHandler;
import org.microprofile.file.listener.FileListener;
import org.microprofile.websocket.WebSocketClient;
import org.microprofile.websocket.WebSocketServer;

public class FileListenerTest {

    public static void main(String[] args) {
        try {

            FileListener listener1 = new FileListener("D:/aaa/");
            LocalFileAdaptor localFileHandler1 = new LocalFileAdaptor(listener1.getBasePath());
            FileEventHandler fileEventHandler1 = new FileEventHandler(localFileHandler1);
            RemoteMessageHandler remoteMessageHandler1 = new RemoteMessageHandler(fileEventHandler1);
            WebSocketServer wss = new WebSocketServer(1000, 1, remoteMessageHandler1);
            wss.asyncListen();
            listener1.addEventHandler(fileEventHandler1);
            listener1.start();

            FileListener listener = new FileListener("D:/bbb/");
            LocalFileAdaptor localFileHandler = new LocalFileAdaptor(listener.getBasePath());
            FileEventHandler fileEventHandler = new FileEventHandler(localFileHandler);
            RemoteMessageHandler remoteMessageHandler = new RemoteMessageHandler(fileEventHandler);
            WebSocketClient ws = new WebSocketClient("localhost", 1000, remoteMessageHandler);

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