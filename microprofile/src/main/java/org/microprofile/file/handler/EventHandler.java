package org.microprofile.file.handler;

public interface EventHandler {
    public void process(EventType eventType, String filePath);
}