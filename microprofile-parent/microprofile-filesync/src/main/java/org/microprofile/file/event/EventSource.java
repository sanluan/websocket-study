package org.microprofile.file.event;

import java.util.ArrayList;
import java.util.List;

/**
 * 事件来源上下文：这条变更由哪个节点产生（{@link #getSource()}），经过哪些节点转发（{@link #getRelayers()}）。
 * <p>
 * 事件报文里跟着路径一起传输，A → B → C 的链路上 C 看到的是 A 的身份 + "由 B 转发"。
 * </p>
 */
public class EventSource {

    private NodeInfo source;
    private List<NodeInfo> relayers;

    public EventSource() {
        super();
    }

    /**
     * @param source
     *            产生变更的节点
     * @param relayers
     *            转发节点链
     */
    public EventSource(NodeInfo source, List<NodeInfo> relayers) {
        super();
        this.source = source;
        this.relayers = relayers;
    }

    /**
     * @return 拷贝一份（节点身份也拷贝，避免共享实例被改动）
     */
    public EventSource copy() {
        EventSource copy = new EventSource();
        copy.source = null == source ? null : source.copy();
        if (null != relayers) {
            List<NodeInfo> list = new ArrayList<>(relayers.size());
            for (NodeInfo relayer : relayers) {
                if (null != relayer) {
                    list.add(relayer.copy());
                }
            }
            copy.relayers = list;
        }
        return copy;
    }

    /**
     * 追加一个转发节点（本节点转发时调用）
     *
     * @param relayer
     */
    public void addRelayer(NodeInfo relayer) {
        if (null == relayer) {
            return;
        }
        if (null == relayers) {
            relayers = new ArrayList<>();
        }
        relayers.add(relayer.copy());
    }

    /**
     * @return "来源节点 via 转发节点..."
     */
    public String describe() {
        StringBuilder builder = new StringBuilder(null == source ? "unknown" : source.describe());
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
     * @return 产生变更的节点
     */
    public NodeInfo getSource() {
        return source;
    }

    /**
     * @param source
     */
    public void setSource(NodeInfo source) {
        this.source = source;
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

    @Override
    public String toString() {
        return "EventSource [" + describe() + "]";
    }
}
