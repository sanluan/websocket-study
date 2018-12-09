package org.microprofile.file.handler;

public enum EventType {
    FILE_DELETE(false), FILE_CREATE(false), FILE_MODIFY(false), DIRECTORY_CREATE(true), DIRECTORY_DELETE(true);
    private boolean directory;

    private EventType(boolean directory) {
        this.directory = directory;
    }

    public boolean isDirectory() {
        return directory;
    }

    public void setDirectory(boolean directory) {
        this.directory = directory;
    }
}
