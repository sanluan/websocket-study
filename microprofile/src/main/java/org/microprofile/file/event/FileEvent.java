package org.microprofile.file.event;

public class FileEvent {
    private EventType eventType;
    private String filePath;

    public FileEvent(EventType eventType, String filePath) {
        super();
        this.eventType = eventType;
        this.filePath = filePath;
    }

    public EventType getEventType() {
        return eventType;
    }

    public void setEventType(EventType eventType) {
        this.eventType = eventType;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    @Override
    public String toString() {
        return "FileEvent [eventType=" + eventType + ", filePath=" + filePath + "]";
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((eventType == null) ? 0 : eventType.hashCode());
        result = prime * result + ((filePath == null) ? 0 : filePath.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        FileEvent other = (FileEvent) obj;
        if (eventType != other.eventType)
            return false;
        if (filePath == null) {
            if (other.filePath != null)
                return false;
        } else if (!filePath.equals(other.filePath))
            return false;
        return true;
    }

    public static enum EventType {
        FILE_CREATE((byte) 0, false), FILE_MODIFY((byte) 1, false), FILE_DELETE((byte) 2, false), DIRECTORY_CREATE((byte) 3,
                true), DIRECTORY_DELETE((byte) 4, true);
        private byte code;
        private boolean directory;

        private EventType(byte code, boolean directory) {
            this.code = code;
            this.directory = directory;
        }

        public byte getCode() {
            return code;
        }

        public boolean isDirectory() {
            return directory;
        }
    }
}
