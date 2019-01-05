package org.microprofile.file.message;

public class FileChecksum {
    private String filePath;
    private boolean directory;
    private long fileSize;
    private byte[] checksum;

    /**
     * 
     */
    public FileChecksum() {
        super();
    }

    /**
     * @param filePath
     * @param directory
     * @param fileSize
     */
    public FileChecksum(String filePath, boolean directory, long fileSize) {
        super();
        this.filePath = filePath;
        this.directory = directory;
        this.fileSize = fileSize;
    }

    /**
     * @return the filePath
     */
    public String getFilePath() {
        return filePath;
    }

    /**
     * @param filePath
     *            the filePath to set
     */
    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    /**
     * @return the directory
     */
    public boolean isDirectory() {
        return directory;
    }

    /**
     * @param directory
     *            the directory to set
     */
    public void setDirectory(boolean directory) {
        this.directory = directory;
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

    /**
     * @return the checksum
     */
    public byte[] getChecksum() {
        return checksum;
    }

    /**
     * @param checksum
     *            the checksum to set
     */
    public void setChecksum(byte[] checksum) {
        this.checksum = checksum;
    }
}