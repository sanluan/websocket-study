package org.microprofile.test;

import org.microprofile.file.handler.ArchiveEventHandler;
import org.microprofile.file.handler.FileSyncEventHandler;
import org.microprofile.file.listener.FileListener;
import org.microprofile.websocket.WebSocketClient;

public class ArchiveTest {

    public static void main(String[] args) {
        try {
            FileListener listener = new FileListener("D:/ccc/");
            FileSyncEventHandler fileEventHandler = new FileSyncEventHandler(listener.getLocalFileAdaptor());
            ArchiveEventHandler archiveEventHandler = new ArchiveEventHandler("D:/archive", listener.getLocalFileAdaptor());
            WebSocketClient ws = new WebSocketClient("localhost", 1000, "/", fileEventHandler.getRemoteMessageHandler(false)); // 使用archiveEventHandler..getRemoteMessageHandler(false)
                                                                                                                               // 可以实现只收不发的归档模式
            ws.asyncListen();
            listener.addEventHandler(fileEventHandler);// 接收发送
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