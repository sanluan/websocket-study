package org.microprofile.file.message;

public class FileBlockMessage {
    private String filePath;
    private long index;
    private long blockSize;
    private byte[] data;

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
     * @return the index
     */
    public long getIndex() {
        return index;
    }

    /**
     * @param index
     *            the index to set
     */
    public void setIndex(long index) {
        this.index = index;
    }

    /**
     * @return the blockSize
     */
    public long getBlockSize() {
        return blockSize;
    }

    /**
     * @param blockSize
     *            the blockSize to set
     */
    public void setBlockSize(long blockSize) {
        this.blockSize = blockSize;
    }

    /**
     * @return the data
     */
    public byte[] getData() {
        return data;
    }

    /**
     * @param data
     *            the data to set
     */
    public void setData(byte[] data) {
        this.data = data;
    }

}