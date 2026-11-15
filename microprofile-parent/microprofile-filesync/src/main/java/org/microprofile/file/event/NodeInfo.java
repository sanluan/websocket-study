package org.microprofile.file.event;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * 节点（机器）身份信息：启动文件监听时采集一次并缓存，随后附加到每一个 {@link FileEvent}。
 * <p>
 * 采集尽量多的信息：计算机名、所有网卡地址、网卡 MAC、登录用户、操作系统、Java 版本、进程号、
 * 启动时间、CPU 核数、最大堆；并生成一个节点标识 {@code nodeId}（计算机名-进程号-启动时间）。
 * </p>
 * <p>
 * 事件在网络上传下去时，{@code relayers} 记录"这条消息经过哪些节点转发"，于是 A → B → C 的链路上，
 * C 拿到的是 A 的身份 + "由 B 转发"。
 * </p>
 */
public class NodeInfo {

    private static volatile NodeInfo local;

    private String nodeId;
    private String hostName;
    private String address;
    private String mac;
    private String userName;
    private String os;
    private String javaVersion;
    private int pid;
    private long startTime;
    private int cpuCores;
    private long maxHeapMb;
    /**
     * 转发链：谁把这条消息转过来的（仅事件上下文里出现，节点自身身份信息里为空）
     */
    private List<NodeInfo> relayers;

    public NodeInfo() {
        super();
    }

    /**
     * 手工构造一个节点身份（容器、多实例、测试场景）
     *
     * @param nodeId
     * @param hostName
     * @param address
     */
    public NodeInfo(String nodeId, String hostName, String address) {
        super();
        this.nodeId = nodeId;
        this.hostName = hostName;
        this.address = address;
    }

    /**
     * @return 本进程的节点身份，第一次调用时采集并缓存
     */
    public static NodeInfo local() {
        NodeInfo info = local;
        if (null == info) {
            synchronized (NodeInfo.class) {
                if (null == local) {
                    local = collect();
                }
                info = local;
            }
        }
        return info;
    }

    /**
     * 替换本进程的节点身份（默认用采集结果）
     *
     * @param nodeInfo
     */
    public static void setLocal(NodeInfo nodeInfo) {
        local = nodeInfo;
    }

    private static NodeInfo collect() {
        NodeInfo info = new NodeInfo();
        long now = System.currentTimeMillis();
        String hostName = null;
        String primaryAddress = null;
        try {
            InetAddress address = InetAddress.getLocalHost();
            hostName = address.getHostName();
            primaryAddress = address.getHostAddress();
        } catch (UnknownHostException e) {
            // 取不到就退到环境变量
        }
        if (null == hostName || 0 == hostName.length()) {
            hostName = System.getenv("COMPUTERNAME");
        }
        if (null == hostName || 0 == hostName.length()) {
            hostName = System.getenv("HOSTNAME");
        }
        if (null == hostName || 0 == hostName.length()) {
            hostName = "unknown-host";
        }
        info.setHostName(hostName);
        List<String> addresses = collectAddresses(primaryAddress);
        StringBuilder addressText = new StringBuilder();
        for (String address : addresses) {
            if (0 < addressText.length()) {
                addressText.append(',');
            }
            addressText.append(address);
        }
        info.setAddress(addressText.toString());
        info.setMac(collectMac());
        info.setUserName(System.getProperty("user.name"));
        info.setOs(System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                + System.getProperty("os.arch"));
        info.setJavaVersion(System.getProperty("java.version"));
        info.setPid(collectPid());
        info.setCpuCores(Runtime.getRuntime().availableProcessors());
        info.setMaxHeapMb(Runtime.getRuntime().maxMemory() / (1024 * 1024));
        info.setStartTime(now);
        info.setNodeId(hostName + "-" + info.getPid() + "-" + Long.toHexString(now));
        return info;
    }

    private static List<String> collectAddresses(String primaryAddress) {
        List<String> result = new ArrayList<>();
        if (null != primaryAddress && 0 < primaryAddress.length()) {
            result.add(primaryAddress);
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (null != interfaces && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    String text = addresses.nextElement().getHostAddress();
                    if (null == text) {
                        continue;
                    }
                    int scope = text.indexOf('%');
                    if (0 < scope) {
                        text = text.substring(0, scope);
                    }
                    if (!result.contains(text)) {
                        result.add(text);
                    }
                }
            }
        } catch (SocketException e) {
            // 没有权限枚举网卡时忽略
        }
        return result;
    }

    private static String collectMac() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (null != interfaces && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }
                byte[] hardware = networkInterface.getHardwareAddress();
                if (null == hardware || 7 < hardware.length || 6 > hardware.length) {
                    continue;
                }
                StringBuilder builder = new StringBuilder();
                for (byte b : hardware) {
                    if (0 < builder.length()) {
                        builder.append('-');
                    }
                    builder.append(String.format("%02X", b));
                }
                return builder.toString();
            }
        } catch (SocketException e) {
            // 忽略
        }
        return null;
    }

    private static int collectPid() {
        String runtime = ManagementFactory.getRuntimeMXBean().getName();
        if (null == runtime) {
            return 0;
        }
        int at = runtime.indexOf('@');
        String pidText = 0 < at ? runtime.substring(0, at) : runtime;
        try {
            return Integer.parseInt(pidText);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * @return 拷贝一份身份信息（不含转发链），用于对外序列化，避免改动共享实例
     */
    public NodeInfo copy() {
        NodeInfo info = new NodeInfo();
        info.nodeId = nodeId;
        info.hostName = hostName;
        info.address = address;
        info.mac = mac;
        info.userName = userName;
        info.os = os;
        info.javaVersion = javaVersion;
        info.pid = pid;
        info.startTime = startTime;
        info.cpuCores = cpuCores;
        info.maxHeapMb = maxHeapMb;
        return info;
    }

    /**
     * 第一个地址，没有则空串。
     * <p>
     * 故意不用 {@code getXxx} 形式的命名：序列化到报文里时会多出一个没有对应 setter 的字段，
     * 反序列化那头会直接报 {@code UnrecognizedPropertyException}。
     * </p>
     *
     * @return
     */
    public String firstAddress() {
        if (null == address || address.isEmpty()) {
            return "";
        }
        int index = address.indexOf(',');
        return 0 > index ? address : address.substring(0, index);
    }

    /**
     * @return 便于日志/记录展示的短标识：计算机名/首个 IP
     */
    public String describe() {
        return (null == hostName ? "unknown" : hostName) + "/" + firstAddress();
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
     * @return the hostName 计算机名
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
     * @return 所有网卡地址，逗号分隔
     */
    public String getAddress() {
        return address;
    }

    /**
     * @param address
     */
    public void setAddress(String address) {
        this.address = address;
    }

    /**
     * @return the mac
     */
    public String getMac() {
        return mac;
    }

    /**
     * @param mac
     */
    public void setMac(String mac) {
        this.mac = mac;
    }

    /**
     * @return the userName
     */
    public String getUserName() {
        return userName;
    }

    /**
     * @param userName
     */
    public void setUserName(String userName) {
        this.userName = userName;
    }

    /**
     * @return the os
     */
    public String getOs() {
        return os;
    }

    /**
     * @param os
     */
    public void setOs(String os) {
        this.os = os;
    }

    /**
     * @return the javaVersion
     */
    public String getJavaVersion() {
        return javaVersion;
    }

    /**
     * @param javaVersion
     */
    public void setJavaVersion(String javaVersion) {
        this.javaVersion = javaVersion;
    }

    /**
     * @return the pid
     */
    public int getPid() {
        return pid;
    }

    /**
     * @param pid
     */
    public void setPid(int pid) {
        this.pid = pid;
    }

    /**
     * @return the startTime
     */
    public long getStartTime() {
        return startTime;
    }

    /**
     * @param startTime
     */
    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    /**
     * @return the cpuCores
     */
    public int getCpuCores() {
        return cpuCores;
    }

    /**
     * @param cpuCores
     */
    public void setCpuCores(int cpuCores) {
        this.cpuCores = cpuCores;
    }

    /**
     * @return the maxHeapMb
     */
    public long getMaxHeapMb() {
        return maxHeapMb;
    }

    /**
     * @param maxHeapMb
     */
    public void setMaxHeapMb(long maxHeapMb) {
        this.maxHeapMb = maxHeapMb;
    }

    /**
     * @return the relayers
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
        return "NodeInfo [nodeId=" + nodeId + ", hostName=" + hostName + ", address=" + address + ", mac=" + mac
                + ", userName=" + userName + ", os=" + os + ", pid=" + pid + ", relayers="
                + (null == relayers ? 0 : relayers.size()) + "]";
    }
}
