package org.microprofile.file.event;

public class FileEvent {
    private EventType eventType;
    private String filePath;
    private long lastModify;
    private long fileSize;

    public FileEvent(EventType eventType, String filePath, long lastModify, long fileSize) {
        super();
        this.eventType = eventType;
        this.filePath = filePath;
        this.lastModify = lastModify;
        this.fileSize = fileSize;
    }

    /**
     * @return eventType
     */
    public EventType getEventType() {
        return eventType;
    }

    /**
     * @param eventType
     */
    public void setEventType(EventType eventType) {
        this.eventType = eventType;
    }

    /**
     * @return
     */
    public String getFilePath() {
        return filePath;
    }

    /**
     * @param filePath
     */
    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    /**
     * @return the lastModify
     */
    public long getLastModify() {
        return lastModify;
    }

    /**
     * @param lastModify
     *            the lastModify to set
     */
    public void setLastModify(long lastModify) {
        this.lastModify = lastModify;
    }

    /**
     * @return the fileSize
     */
    public long getFileSize() {
        return fileSize;
    }

    /**
     * @param fileSize
     *            the fileSize to set
     */
    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "FileEvent [eventType=" + eventType + ", filePath=" + filePath + ", lastModify=" + lastModify + ", fileSize="
                + fileSize + "]";
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((eventType == null) ? 0 : eventType.hashCode());
        result = prime * result + ((filePath == null) ? 0 : filePath.hashCode());
        result = prime * result + (int) (fileSize ^ (fileSize >>> 32));
        result = prime * result + (int) (lastModify ^ (lastModify >>> 32));
        return result;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Object#equals(java.lang.Object)
     */
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
        if (fileSize != other.fileSize)
            return false;
        if (lastModify != other.lastModify)
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
