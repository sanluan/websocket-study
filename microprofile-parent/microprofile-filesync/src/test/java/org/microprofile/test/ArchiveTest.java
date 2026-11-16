package org.microprofile.test;

import org.microprofile.file.handler.ArchiveEventHandler;
import org.microprofile.file.listener.FileListener;
import org.microprofile.websocket.WebSocketClient;

public class ArchiveTest {

    public static void main(String[] args) {
        try {
            ArchiveEventHandler archiveEventHandler = new ArchiveEventHandler("D:/archive",
                    FileListener.createLocalFileAdaptor("D:/ccc", FileListener.DEFULT_BLOCK_SIZE));
            WebSocketClient ws = new WebSocketClient("localhost", 1000, "/", archiveEventHandler.getRemoteMessageHandler(false)); // 使用archiveEventHandler..getRemoteMessageHandler(false)
                                                                                                                                  // 可以实现只收不发的归档模式
            ws.asyncListen();
            Thread.sleep(1000 * 1000);
            ws.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}