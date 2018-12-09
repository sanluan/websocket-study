package org.microprofile.file.message;

import java.util.List;

public class FileBlockChecksumListMessage {
    private List<FileBlockChecksum> fileList;

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
        private String filePath;
        private long index;
        private long blockSize;
        private byte[] checksum;

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