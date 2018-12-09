package org.microprofile.file.event;

public interface EventHandler {
    public void process(FileEvent event);

    public void cache(FileEvent event);
}