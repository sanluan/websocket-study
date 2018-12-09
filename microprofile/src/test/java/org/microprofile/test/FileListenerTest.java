package org.microprofile.test;

import org.microprofile.file.handler.EventHandler;
import org.microprofile.file.handler.EventType;
import org.microprofile.file.listener.FileListener;

public class FileListenerTest {

    public static void main(String[] args) {
        try {
            FileListener listener = new FileListener("D:/Temp/", new EventHandler() {
                @Override
                public void process(EventType eventType, String filePath) {
                    System.out.println(eventType + "\t:\t" + filePath);
                }
            });
            listener.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}