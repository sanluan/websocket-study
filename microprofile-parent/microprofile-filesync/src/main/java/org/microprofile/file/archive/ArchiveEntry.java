package org.microprofile.file.archive;

import java.util.List;

/**
 * 一条归档记录（与归档目录里的 {@code <时间>-<序号>.json} 一一对应）。
 * <p>
 * {@code type=FULL} 表示一份全量快照，内容在 {@code <name>.full}；
 * {@code type=PATCH} 表示一次块级增量，内容在 {@code <name>.b<index>} 若干块里。
 * </p>
 */
public class ArchiveEntry {

    public final static String TYPE_FULL = "FULL";
    public final static String TYPE_PATCH = "PATCH";

    private String path;
    private String name;
    private String type;
    private long time;
    private String timeText;
    private int sequence;
    private String ip;
    /**
     * 来源节点：计算机名与节点标识（{@code ip} 是它的首个地址）
     */
    private String hostName;
    private String nodeId;
    /**
     * 转发链：这条变更经过哪些节点转发过来（本端直接产生时为空）
     */
    private List<String> relayers;
    private long fileSize;
    private int blockSize;
    private List<BlockRef> blocks;

    /**
     * @return the path
     */
    public String getPath() {
        return path;
    }

    /**
     * @param path
     */
    public void setPath(String path) {
        this.path = path;
    }

    /**
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * @param name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * @return the type
     */
    public String getType() {
        return type;
    }

    /**
     * @param type
     */
    public void setType(String type) {
        this.type = type;
    }

    /**
     * @return the time
     */
    public long getTime() {
        return time;
    }

    /**
     * @param time
     */
    public void setTime(long time) {
        this.time = time;
    }

    /**
     * @return the timeText
     */
    public String getTimeText() {
        return timeText;
    }

    /**
     * @param timeText
     */
    public void setTimeText(String timeText) {
        this.timeText = timeText;
    }

    /**
     * @return the sequence
     */
    public int getSequence() {
        return sequence;
    }

    /**
     * @param sequence
     */
    public void setSequence(int sequence) {
        this.sequence = sequence;
    }

    /**
     * @return the ip
     */
    public String getIp() {
        return ip;
    }

    /**
     * @param ip
     */
    public void setIp(String ip) {
        this.ip = ip;
    }

    /**
     * @return the hostName
     */
    public String getHostName() {
        return hostName;
    }

    /**
     * @param hostName
     */
    public void setHostName(String hostName) {
        this.hostName = hostName;
    }

    /**
     * @return the nodeId
     */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * @param nodeId
     */
    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    /**
     * @return the relayers
     */
    public List<String> getRelayers() {
        return relayers;
    }

    /**
     * @param relayers
     */
    public void setRelayers(List<String> relayers) {
        this.relayers = relayers;
    }

    /**
     * @return the fileSize
     */
    public long getFileSize() {
        return fileSize;
    }

    /**
     * @param fileSize
     */
    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    /**
     * @return the blockSize
     */
    public int getBlockSize() {
        return blockSize;
    }

    /**
     * @param blockSize
     */
    public void setBlockSize(int blockSize) {
        this.blockSize = blockSize;
    }

    /**
     * @return the blocks
     */
    public List<BlockRef> getBlocks() {
        return blocks;
    }

    /**
     * @param blocks
     */
    public void setBlocks(List<BlockRef> blocks) {
        this.blocks = blocks;
    }

    @Override
    public String toString() {
        return "ArchiveEntry [path=" + path + ", name=" + name + ", type=" + type + ", timeText=" + timeText + ", ip=" + ip
                + ", hostName=" + hostName + ", nodeId=" + nodeId + ", relayers=" + relayers + ", fileSize=" + fileSize
                + ", blockSize=" + blockSize + ", blocks=" + blocks + "]";
    }
}
