package org.microprofile.file.listener;

import java.io.File;
import java.io.FileFilter;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.io.IOCase;
import org.apache.commons.io.monitor.FileAlterationListener;
import org.apache.commons.io.monitor.FileAlterationMonitor;
import org.apache.commons.io.monitor.FileAlterationObserver;
import org.microprofile.file.event.EventHandler;
import org.microprofile.file.event.FileEvent;
import org.microprofile.file.event.FileEvent.EventType;
import org.microprofile.file.event.NodeInfo;
import org.microprofile.file.handler.LocalFileAdaptor;

/**
 * FileListener
 * 
 */
public class FileListener implements FileAlterationListener {
    /**
     */
    public final static int DEFULT_BLOCK_SIZE = 1024 * 1024;

    /**
     * 默认轮询间隔。commons-io 的 FileAlterationMonitor 默认 10 秒，
     * 本地改动要等 10 秒才推送，这里改成 1 秒
     */
    public final static long DEFAULT_POLLING_INTERVAL = 1000L;

    private String basePath;
    private List<EventHandler> eventHandlers;
    private FileAlterationObserver observer;
    private FileAlterationMonitor fileMonitor;
    private LocalFileAdaptor localFileAdaptor;
    private long pollingInterval = DEFAULT_POLLING_INTERVAL;
    private boolean started;
    /**
     * 本节点身份：启动监听时采集一次并缓存，写进每一条 FileEvent
     */
    private volatile NodeInfo nodeInfo;

    /**
     * @param listenPath
     */
    public FileListener(String listenPath) {
        this(new FileAlterationObserver(listenPath), DEFULT_BLOCK_SIZE);
    }

    /**
     * @param listenPath
     * @param blockSize
     * @param fileFilter
     */
    public FileListener(String listenPath, int blockSize, final FileFilter fileFilter) {
        this(new FileAlterationObserver(listenPath, fileFilter), blockSize);
    }

    /**
     * @param listenPath
     * @param blockSize
     * @param fileFilter
     * @param caseSensitivity
     */
    public FileListener(String listenPath, int blockSize, final FileFilter fileFilter, final IOCase caseSensitivity) {
        this(new FileAlterationObserver(listenPath, fileFilter, caseSensitivity), blockSize);
    }

    private FileListener(FileAlterationObserver observer, int blockSize) {
        super();
        this.observer = observer;
        init(blockSize);
    }

    private void init(int blockSize) {
        File directory = observer.getDirectory();
        basePath = directory.getAbsolutePath();
        localFileAdaptor = new LocalFileAdaptor(basePath, blockSize);
        observer.addListener(this);
    }

    private void process(EventType eventType, File file) {
        if (null != eventHandlers) {
            String filePath = localFileAdaptor.getRelativeFilePath(file);
            NodeInfo source = getNodeInfo();
            for (EventHandler eventHandler : eventHandlers) {
                // FileEvent 的签名是 (eventType, filePath, lastModify, fileSize)，
                // 原来这里传成了 (size, lastModified)，导致下游把上万兆的时间戳当作文件大小
                eventHandler.handle(new FileEvent(eventType, filePath, file.lastModified(), file.length(), source));
            }
        }
    }

    /**
     * @return 本节点身份信息（第一次调用时采集并缓存：计算机名、所有网卡地址、MAC、用户、操作系统等）
     */
    public NodeInfo getNodeInfo() {
        NodeInfo info = nodeInfo;
        if (null == info) {
            info = NodeInfo.local();
            nodeInfo = info;
        }
        return info;
    }

    /**
     * 指定本节点身份（默认为采集到的本机信息；容器/多实例/测试时可自定义）
     *
     * @param nodeInfo
     */
    public void setNodeInfo(NodeInfo nodeInfo) {
        this.nodeInfo = nodeInfo;
    }

    @Override
    public void onFileCreate(File file) {
        process(EventType.FILE_CREATE, file);
    }

    @Override
    public void onFileChange(File file) {
        process(EventType.FILE_MODIFY, file);
    }

    @Override
    public void onFileDelete(File file) {
        process(EventType.FILE_DELETE, file);
    }

    @Override
    public void onDirectoryCreate(File directory) {
        process(EventType.DIRECTORY_CREATE, directory);
    }

    @Override
    public void onDirectoryChange(File directory) {
    }

    @Override
    public void onDirectoryDelete(File directory) {
        process(EventType.DIRECTORY_DELETE, directory);
    }

    @Override
    public void onStart(FileAlterationObserver observer) {

    }

    @Override
    public void onStop(FileAlterationObserver observer) {
    }

    /**
     * @return the localFileAdaptor
     */
    public LocalFileAdaptor getLocalFileAdaptor() {
        return localFileAdaptor;
    }

    /**
     * @param eventHandler
     *            the eventHandler to add
     */
    public void addEventHandler(EventHandler eventHandler) {
        if (null != eventHandler) {
            if (null == eventHandlers) {
                eventHandlers = new LinkedList<>();
            }
            eventHandlers.add(eventHandler);
        }
    }

    /**
     * 设置文件监听轮询间隔（毫秒），必须在 {@link #start()} 之前调用。
     * <p>
     * 间隔越小同步越及时；远程写入后用于抑制回传的时间窗（
     * {@link org.microprofile.file.handler.RemoteMessageHandler#setEchoSuppressMillis(long)}）
     * 应大于该间隔。
     * </p>
     *
     * @param pollingInterval
     */
    public void setPollingInterval(long pollingInterval) {
        if (0 >= pollingInterval) {
            throw new IllegalArgumentException("pollingInterval must be positive");
        }
        if (started) {
            throw new IllegalStateException("listener already started, can't change pollingInterval");
        }
        this.pollingInterval = pollingInterval;
    }

    /**
     * @return 当前轮询间隔（毫秒）
     */
    public long getPollingInterval() {
        return pollingInterval;
    }

    /**
     * @throws Exception
     */
    public void start() throws Exception {
        // 启动监听时把节点身份采集下来并缓存，之后每条事件都带这份信息
        getNodeInfo();
        fileMonitor = new FileAlterationMonitor(pollingInterval);
        fileMonitor.addObserver(observer);
        fileMonitor.start();
        started = true;
    }

    /**
     * @throws Exception
     */
    public void stop() throws Exception {
        localFileAdaptor.stop();
        if (null != fileMonitor) {
            fileMonitor.stop();
        }
        started = false;
    }

}
