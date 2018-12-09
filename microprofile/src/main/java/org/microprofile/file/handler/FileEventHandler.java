package org.microprofile.file.handler;

import org.microprofile.common.utils.EventQueue;
import org.microprofile.file.event.EventHandler;
import org.microprofile.file.event.FileEvent;

public class FileEventHandler implements EventHandler {
    private EventQueue<FileEvent> eventQueue;
    private FileMessageHandler fileMessageHandler;

    public FileEventHandler(int cacheSize, FileMessageHandler fileMessageHandler) {
        super();
        this.fileMessageHandler = fileMessageHandler;
        this.eventQueue = new EventQueue<>(cacheSize);
    }

    @Override
    public void process(FileEvent event) {
        if (eventQueue.contains(event)) {
            eventQueue.remove(event);
        } else {
            fileMessageHandler.sendEvent(event);
        }
    }

    @Override
    public void cache(FileEvent event) {
        eventQueue.add(event);
    }
}