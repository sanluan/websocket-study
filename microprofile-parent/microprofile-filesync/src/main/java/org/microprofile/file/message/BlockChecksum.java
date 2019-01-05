package org.microprofile.file.message;

public class BlockChecksum {
    private int blockIndex;
    private byte[] checksum;

    /**
     * 
     */
    public BlockChecksum() {
        super();
    }

    public BlockChecksum(int blockIndex, byte[] checksum) {
        super();
        this.blockIndex = blockIndex;
        this.checksum = checksum;
    }

    /**
     * @return the block index
     */
    public int getBlockIndex() {
        return blockIndex;
    }

    /**
     * @param blockIndex
     *            the block index to set
     */
    public void setBlockIndex(int blockIndex) {
        this.blockIndex = blockIndex;
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