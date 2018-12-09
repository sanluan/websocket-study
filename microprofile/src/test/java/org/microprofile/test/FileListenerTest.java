package org.microprofile.test;

import org.microprofile.file.event.EventHandler;
import org.microprofile.file.event.FileEvent;
import org.microprofile.file.listener.FileListener;

public class FileListenerTest {

    public static void main(String[] args) {
        try {
            FileListener listener = new FileListener("D:/Temp/", new EventHandler() {

                @Override
                public void process(FileEvent event) {
                    System.out.println(event);
                }

                @Override
                public void cache(FileEvent event) {

                }

            });
            listener.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}