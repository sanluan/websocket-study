package org.microprofile.file.archive;

import org.microprofile.file.event.NodeInfo;

/**
 * 删除记录接口：把"谁在什么时候删了哪个文件"记进归档。
 * <p>
 * 删除事件在协议层才拿得到来源节点信息，所以由 {@code RemoteMessageHandler} 回调本接口；
 * 本端删的（监听器发现）由归档处理器自己记，来源取本节点身份。
 * </p>
 */
public interface DeleteRecorder {

    /**
     * @param filePath
     *            同步根的相对路径
     * @param source
     *            删除动作的发起节点（可能是原始节点，也可能经过转发，<code>source.getRelayers()</code>
     *            里是转发链）；null 表示来源未知
     */
    public void recordDelete(String filePath, NodeInfo source);
}
