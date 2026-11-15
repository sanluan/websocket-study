package org.microprofile.test;

import org.microprofile.file.handler.ArchiveEventHandler;
import org.microprofile.file.listener.FileListener;
import org.microprofile.websocket.WebSocketClient;

public class ArchiveTest {

    public static void main(String[] args) {
        try {
            FileListener listener = new FileListener("D:/ccc/");
            ArchiveEventHandler archiveEventHandler = new ArchiveEventHandler("D:/archive",listener.getLocalFileAdaptor());
            WebSocketClient ws = new WebSocketClient("localhost", 1000, "/", archiveEventHandler.getRemoteMessageHandler(false));
            ws.asyncListen();
            listener.addEventHandler(archiveEventHandler);
            listener.start();
            Thread.sleep(1000 * 1000);
            listener.stop();
            ws.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}