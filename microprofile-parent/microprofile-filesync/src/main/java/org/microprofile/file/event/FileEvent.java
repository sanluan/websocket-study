package org.microprofile.file.event;

import java.util.List;

public class FileEvent {
    private EventType eventType;
    private String filePath;
    private long lastModify;
    private long fileSize;
    /**
     * 产生这条变更的节点（附加信息，不参与 equals/hashCode）
     */
    private NodeInfo nodeInfo;
    /**
     * 转发链：这条变更经过哪些节点转过来（本地直接产生的为空）
     */
    private List<NodeInfo> relayers;

    public FileEvent(EventType eventType, String filePath, long lastModify, long fileSize) {
        this(eventType, filePath, lastModify, fileSize, null, null);
    }

    public FileEvent(EventType eventType, String filePath, long lastModify, long fileSize, NodeInfo nodeInfo) {
        this(eventType, filePath, lastModify, fileSize, nodeInfo, null);
    }

    public FileEvent(EventType eventType, String filePath, long lastModify, long fileSize, NodeInfo nodeInfo,
            List<NodeInfo> relayers) {
        super();
        this.eventType = eventType;
        this.filePath = filePath;
        this.lastModify = lastModify;
        this.fileSize = fileSize;
        this.nodeInfo = nodeInfo;
        this.relayers = relayers;
    }

    /**
     * @return 产生这条变更的节点（可能为 null，表示来源未知）
     */
    public NodeInfo getNodeInfo() {
        return nodeInfo;
    }

    /**
     * @param nodeInfo
     */
    public void setNodeInfo(NodeInfo nodeInfo) {
        this.nodeInfo = nodeInfo;
    }

    /**
     * @return 转发链
     */
    public List<NodeInfo> getRelayers() {
        return relayers;
    }

    /**
     * @param relayers
     */
    public void setRelayers(List<NodeInfo> relayers) {
        this.relayers = relayers;
    }

    /**
     * @return "来源节点 via 转发节点1 via 转发节点2" 的可读文本
     */
    public String getSourceLabel() {
        StringBuilder builder = new StringBuilder(null == nodeInfo ? "unknown" : nodeInfo.describe());
        if (null != relayers) {
            for (NodeInfo relayer : relayers) {
                if (null == relayer) {
                    continue;
                }
                builder.append(" via ").append(relayer.describe());
            }
        }
        return builder.toString();
    }

    /**
     * @return eventType
     */
    public EventType getEventType() {
        return eventType;
    }

    /**
     * @param eventType
     */
    public void setEventType(EventType eventType) {
        this.eventType = eventType;
    }

    /**
     * @return
     */
    public String getFilePath() {
        return filePath;
    }

    /**
     * @param filePath
     */
    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    /**
     * @return the lastModify
     */
    public long getLastModify() {
        return lastModify;
    }

    /**
     * @param lastModify
     *            the lastModify to set
     */
    public void setLastModify(long lastModify) {
        this.lastModify = lastModify;
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

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "FileEvent [eventType=" + eventType + ", filePath=" + filePath + ", lastModify=" + lastModify + ", fileSize="
                + fileSize + ", source=" + getSourceLabel() + "]";
    }

    /*
     * 注意：nodeInfo/relayers 故意不参与 hashCode/equals。
     * 回环抑制的"精确匹配"队列用 equals 判断"这条事件是不是刚才对端写出来的同一条"，
     * 而本地监听重新报出来的事件带的是本节点身份，把身份比进去就永远匹配不上。
     */

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((eventType == null) ? 0 : eventType.hashCode());
        result = prime * result + ((filePath == null) ? 0 : filePath.hashCode());
        result = prime * result + (int) (fileSize ^ (fileSize >>> 32));
        result = prime * result + (int) (lastModify ^ (lastModify >>> 32));
        return result;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        FileEvent other = (FileEvent) obj;
        if (eventType != other.eventType)
            return false;
        if (filePath == null) {
            if (other.filePath != null)
                return false;
        } else if (!filePath.equals(other.filePath))
            return false;
        if (fileSize != other.fileSize)
            return false;
        if (lastModify != other.lastModify)
            return false;
        return true;
    }

    public static enum EventType {
        FILE_CREATE((byte) 0, false), FILE_MODIFY((byte) 1, false), FILE_DELETE((byte) 2, false), DIRECTORY_CREATE((byte) 3,
                true), DIRECTORY_DELETE((byte) 4, true);
        private byte code;
        private boolean directory;

        private EventType(byte code, boolean directory) {
            this.code = code;
            this.directory = directory;
        }

        public byte getCode() {
            return code;
        }

        public boolean isDirectory() {
            return directory;
        }
    }
}
