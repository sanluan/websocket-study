package org.microprofile.file.event;

public interface EventHandler {
    public void handle(FileEvent event);

    public void cache(FileEvent event);
}