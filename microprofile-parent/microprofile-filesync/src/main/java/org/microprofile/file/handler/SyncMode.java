package org.microprofile.file.handler;

/**
 * 文件同步方向开关。
 * <p>
 * 通过 {@link RemoteMessageHandler#setSyncMode(SyncMode)} 或
 * {@link FileEventHandler#setSyncMode(SyncMode)} 设置，默认 {@link #BIDIRECTIONAL}，
 * 即与原有行为完全一致。
 * </p>
 */
public enum SyncMode {

    /**
     * 双向同步：本端变更推送到对端，同时接收并应用对端变更（默认）。
     */
    BIDIRECTIONAL(true, true),

    /**
     * 单向推送：只把本端变更推送到对端，忽略对端发来的所有变更（发布/分发场景）。
     */
    PUSH_ONLY(true, false),

    /**
     * 单向拉取：只接收并应用对端变更，本端变更不外发（下载/镜像场景）。
     * <p>
     * 注意：本端仍会回送“我需要这个文件/这些块”的控制消息，否则无法拉到内容，
     * 但不会发送任何文件内容，也不会推介自己的文件树。
     * </p>
     */
    PULL_ONLY(false, true);

    private final boolean sendEnabled;
    private final boolean receiveEnabled;

    private SyncMode(boolean sendEnabled, boolean receiveEnabled) {
        this.sendEnabled = sendEnabled;
        this.receiveEnabled = receiveEnabled;
    }

    /**
     * @return 是否允许把本端变更/文件树发给对端
     */
    public boolean isSendEnabled() {
        return sendEnabled;
    }

    /**
     * @return 是否允许接收并应用对端变更
     */
    public boolean isReceiveEnabled() {
        return receiveEnabled;
    }

    /**
     * 解析配置值，未知值回退为 {@link #BIDIRECTIONAL}，避免配置写错导致完全不同步。
     * <p>
     * 同时接受 PUSH / UPLOAD / UP 与 PULL / DOWNLOAD / DOWN 等常见写法。
     * </p>
     *
     * @param name
     * @return
     */
    public static SyncMode parse(String name) {
        if (null != name) {
            String value = name.trim().toUpperCase();
            for (SyncMode mode : values()) {
                if (mode.name().equals(value)) {
                    return mode;
                }
            }
            if ("PUSH".equals(value) || "UPLOAD".equals(value) || "UP".equals(value) || "SEND_ONLY".equals(value)) {
                return PUSH_ONLY;
            }
            if ("PULL".equals(value) || "DOWNLOAD".equals(value) || "DOWN".equals(value)
                    || "RECEIVE_ONLY".equals(value)) {
                return PULL_ONLY;
            }
            if ("BOTH".equals(value) || "TWO_WAY".equals(value) || "DUPLEX".equals(value)) {
                return BIDIRECTIONAL;
            }
        }
        return BIDIRECTIONAL;
    }
}
