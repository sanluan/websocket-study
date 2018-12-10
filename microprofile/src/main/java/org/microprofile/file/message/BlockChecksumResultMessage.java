package org.microprofile.file.message;

import java.util.List;

public class BlockChecksumResultMessage {
    private String filePath;
    private long blockSize;
    private List<BlockChecksum> blockList;

    public BlockChecksumResultMessage(String filePath, long blockSize, List<BlockChecksum> blockList) {
        super();
        this.filePath = filePath;
        this.blockSize = blockSize;
        this.blockList = blockList;
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
     * @return the blockList
     */
    public List<BlockChecksum> getBlockList() {
        return blockList;
    }

    /**
     * @param blockList
     *            the blockList to set
     */
    public void setBlockList(List<BlockChecksum> blockList) {
        this.blockList = blockList;
    }

    public static class BlockChecksum {
        private long index;
        private byte[] checksum;

        public BlockChecksum(long index, byte[] checksum) {
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