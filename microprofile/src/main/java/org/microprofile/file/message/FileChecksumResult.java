package org.microprofile.file.message;

public class FileChecksumResult {
    private String filePath;
    private boolean exists;

    /**
     * 
     */
    public FileChecksumResult() {
        super();
    }

    /**
     * @param filePath
     * @param exists
     */
    public FileChecksumResult(String filePath, boolean exists) {
        super();
        this.filePath = filePath;
        this.exists = exists;
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
     * @return the exists
     */
    public boolean isExists() {
        return exists;
    }

    /**
     * @param exists
     *            the exists to set
     */
    public void setExists(boolean exists) {
        this.exists = exists;
    }

}