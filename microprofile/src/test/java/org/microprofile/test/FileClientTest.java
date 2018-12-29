package org.microprofile.test;

import org.microprofile.file.handler.FileEventHandler;
import org.microprofile.file.handler.LocalFileAdaptor;
import org.microprofile.file.listener.FileListener;
import org.microprofile.websocket.WebSocketClient;

public class FileClientTest {

    public static void main(String[] args) {
        try {

            FileEventHandler fileEventHandler = new FileEventHandler(
                    new LocalFileAdaptor("D:/bbb/", FileListener.DEFULT_BLOCK_SIZE));
            WebSocketClient ws = new WebSocketClient("localhost", 1000, "/", fileEventHandler.getRemoteMessageHandler(false));
            ws.asyncListen();
            Thread.sleep(1000 * 1000);
            ws.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}