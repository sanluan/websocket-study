package org.microprofile.file.archive;

/**
 * 归档里的一个块引用：改动落在第 index 块，内容是 index * blockSize 起的 length 个字节。
 */
public class BlockRef {
    private int index;
    private int length;

    public BlockRef() {
        super();
    }

    public BlockRef(int index, int length) {
        super();
        this.index = index;
        this.length = length;
    }

    /**
     * @return the index
     */
    public int getIndex() {
        return index;
    }

    /**
     * @param index
     */
    public void setIndex(int index) {
        this.index = index;
    }

    /**
     * @return the length
     */
    public int getLength() {
        return length;
    }

    /**
     * @param length
     */
    public void setLength(int length) {
        this.length = length;
    }

    @Override
    public String toString() {
        return "BlockRef [index=" + index + ", length=" + length + "]";
    }
}
