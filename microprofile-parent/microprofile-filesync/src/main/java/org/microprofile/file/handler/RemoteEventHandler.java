package org.microprofile.file.handler;

import org.microprofile.file.archive.DeleteRecorder;
import org.microprofile.file.event.NodeInfo;
import org.microprofile.file.filter.FileRuleSet;

public abstract class RemoteEventHandler {
    protected RemoteMessageHandler remoteMessageHandler;

    /**
     * @param master
     * @return the remoteMessageHandler
     */
    public RemoteMessageHandler getRemoteMessageHandler(boolean master) {
        remoteMessageHandler.setMaster(master);
        return remoteMessageHandler;
    }

    /**
     * 同步方向开关（等价于 {@code getRemoteMessageHandler(master).setSyncMode(mode)}）。
     * <p>
     * {@link SyncMode#PUSH_ONLY}：只上传本端变更；{@link SyncMode#PULL_ONLY}：只接收对端变更；
     * {@link SyncMode#BIDIRECTIONAL}：双向（默认）。
     * </p>
     *
     * @param syncMode
     */
    public void setSyncMode(SyncMode syncMode) {
        remoteMessageHandler.setSyncMode(syncMode);
    }

    /**
     * @return 当前同步方向开关
     */
    public SyncMode getSyncMode() {
        return remoteMessageHandler.getSyncMode();
    }

    /**
     * 打开/关闭多向同步（中继），等价于
     * {@code getRemoteMessageHandler(master).setMultiWaySync(multiWay)}。
     * <p>
     * 一个服务端接多个客户端时，在服务端一侧打开，客户端 A 的改动会经由服务端到达 B、C。
     * </p>
     *
     * @param multiWaySync
     */
    public void setMultiWaySync(boolean multiWaySync) {
        remoteMessageHandler.setMultiWaySync(multiWaySync);
    }

    /**
     * @return 是否开启多向同步（中继）
     */
    public boolean isMultiWaySync() {
        return remoteMessageHandler.isMultiWaySync();
    }

    /**
     * 设置删除记录器（归档模式用），等价于
     * {@code getRemoteMessageHandler(master).setDeleteRecorder(recorder)}。
     *
     * @param deleteRecorder
     */
    public void setDeleteRecorder(DeleteRecorder deleteRecorder) {
        remoteMessageHandler.setDeleteRecorder(deleteRecorder);
    }

    /**
     * @return 本端广播规则（白名单/黑名单），等价于
     *         {@code getRemoteMessageHandler(master).getSendRules()}
     */
    public FileRuleSet getSendRules() {
        return remoteMessageHandler.getSendRules();
    }

    /**
     * 设置本端<b>广播</b>规则：不在范围内的文件不公告、不回送内容、也不中继。传 null/空规则表示全部文件。
     *
     * @param sendRules
     */
    public void setSendRules(FileRuleSet sendRules) {
        remoteMessageHandler.setSendRules(sendRules);
    }

    /**
     * @return 本端接收规则，等价于
     *         {@code getRemoteMessageHandler(master).getReceiveRules()}
     */
    public FileRuleSet getReceiveRules() {
        return remoteMessageHandler.getReceiveRules();
    }

    /**
     * 设置本端<b>接收</b>规则：不在范围内的对端变更直接丢弃。传 null/空规则表示接收全部文件。
     *
     * @param receiveRules
     */
    public void setReceiveRules(FileRuleSet receiveRules) {
        remoteMessageHandler.setReceiveRules(receiveRules);
    }

    /**
     * 一次设置收发两个方向的规则
     *
     * @param sendRules
     *            广播规则（null 表示全部广播）
     * @param receiveRules
     *            接收规则（null 表示全部接收）
     */
    public void setRules(FileRuleSet sendRules, FileRuleSet receiveRules) {
        remoteMessageHandler.setSendRules(sendRules);
        remoteMessageHandler.setReceiveRules(receiveRules);
    }

    /**
     * @return 本节点身份信息，等价于 {@code getRemoteMessageHandler(master).getNodeInfo()}
     */
    public NodeInfo getNodeInfo() {
        return remoteMessageHandler.getNodeInfo();
    }

    /**
     * 指定本节点身份信息（默认是采集到的本机信息）。需要与监听器
     * {@link org.microprofile.file.listener.FileListener#setNodeInfo(NodeInfo)}
     * 设置成同一个，否则 事件里的身份和报文里的身份会不一致。
     *
     * @param nodeInfo
     */
    public void setNodeInfo(NodeInfo nodeInfo) {
        remoteMessageHandler.setNodeInfo(nodeInfo);
    }
}
