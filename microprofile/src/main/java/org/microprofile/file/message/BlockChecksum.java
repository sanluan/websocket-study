package org.microprofile.file.message;

public class BlockChecksum {
    private long index;
    private byte[] checksum;

    /**
     * 
     */
    public BlockChecksum() {
        super();
    }

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