package org.microprofile.file.message;

import java.util.List;

public class FileBlockChecksumListMessage {
    private String filePath;
    private long blockSize;
    private List<FileBlockChecksum> fileList;

    public FileBlockChecksumListMessage(String filePath, long blockSize, List<FileBlockChecksum> fileList) {
        super();
        this.filePath = filePath;
        this.blockSize = blockSize;
        this.fileList = fileList;
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
     * @return the fileList
     */
    public List<FileBlockChecksum> getFileList() {
        return fileList;
    }

    /**
     * @param fileList
     *            the fileList to set
     */
    public void setFileList(List<FileBlockChecksum> fileList) {
        this.fileList = fileList;
    }

    public static class FileBlockChecksum {
        private long index;
        private byte[] checksum;

        public FileBlockChecksum(long index, byte[] checksum) {
            super();
            this.index = index;
            this.checksum = checksum;
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
}