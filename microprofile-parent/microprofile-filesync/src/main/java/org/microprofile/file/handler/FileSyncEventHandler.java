package org.microprofile.file.handler;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.microprofile.common.utils.EventQueue;
import org.microprofile.file.event.EventHandler;
import org.microprofile.file.event.EventSource;
import org.microprofile.file.event.FileEvent;
import org.microprofile.file.event.FileEvent.EventType;
import org.microprofile.file.message.BlockChecksum;

public class FileSyncEventHandler extends RemoteEventHandler implements EventHandler {
    protected final Log log = LogFactory.getLog(getClass());

    public final static int DEFULT_CACHE_SIZE = 1000;
    private static byte[] EMPTY_BYTE = new byte[0];
    private static byte HEADER_FILE_CREATE = EventType.FILE_CREATE.getCode();
    private static byte HEADER_FILE_MODIFY = EventType.FILE_MODIFY.getCode();

    private EventQueue<FileEvent> eventQueue;
    private LocalFileAdaptor localFileAdaptor;
    /**
     * 回环抑制登记的旁听者（归档处理器用它拿到对端写入的来源节点）
     */
    private volatile EventHandler echoObserver;

    /**
     * @param localFileHandler
     */
    public FileSyncEventHandler(LocalFileAdaptor localFileHandler) {
        this(localFileHandler, DEFULT_CACHE_SIZE);
    }

    /**
     * @param cacheSize
     * @param localFileAdaptor
     */
    public FileSyncEventHandler(LocalFileAdaptor localFileAdaptor, int cacheSize) {
        super();
        this.eventQueue = new EventQueue<>(cacheSize);
        this.localFileAdaptor = localFileAdaptor;
        this.remoteMessageHandler = new RemoteMessageHandler(this, localFileAdaptor);
    }

    /**
     * @param localFileAdaptor
     * @param syncMode
     *            同步方向开关
     */
    public FileSyncEventHandler(LocalFileAdaptor localFileAdaptor, SyncMode syncMode) {
        this(localFileAdaptor, DEFULT_CACHE_SIZE, syncMode);
    }

    /**
     * @param localFileAdaptor
     * @param cacheSize
     * @param syncMode
     *            同步方向开关
     */
    public FileSyncEventHandler(LocalFileAdaptor localFileAdaptor, int cacheSize, SyncMode syncMode) {
        this(localFileAdaptor, cacheSize);
        setSyncMode(null == syncMode ? SyncMode.BIDIRECTIONAL : syncMode);
    }

    @Override
    public void handle(FileEvent event) {
        if (!remoteMessageHandler.getSyncMode().isSendEnabled()) {
            log.debug("同步模式 " + remoteMessageHandler.getSyncMode() + " 不允许发送，跳过 " + event);
            return;
        }
        if (!event.getEventType().isDirectory()
                && !remoteMessageHandler.canSend(event.getFilePath(), event.getFileSize(), event.getLastModify())) {
            // 本端广播规则排除的文件：不公告、不回送内容。目录事件没有内容，始终同步
            return;
        }
        if (remoteMessageHandler.isRemoteWriteEcho(event.getFilePath())) {
            // 该文件刚由对端写入本地，本地监听不应把它再回传，否则两端会互推/来回覆盖
            log.debug("skip remote echo " + event);
            return;
        }
        if (eventQueue.contains(event)) {
            eventQueue.remove(event);
            log.info("skip " + event);
        } else if (remoteMessageHandler.hasSession()) {
            log.info("deal " + event);
            byte code = event.getEventType().getCode();
            // 事件自带节点信息（监听器启动时采集并缓存），随报文一起发给对端
            EventSource source = new EventSource(null == event.getNodeInfo() ? getNodeInfo() : event.getNodeInfo(),
                    event.getRelayers());
            if (HEADER_FILE_CREATE == code || HEADER_FILE_MODIFY == code) {
                File file = localFileAdaptor.getFile(event.getFilePath());
                int blockSize = localFileAdaptor.getBlockSize();
                if (blockSize > event.getFileSize()) {
                    try {
                        remoteMessageHandler.sendBlock(null, code, event.getFilePath(), event.getFileSize(), -1, 0,
                                FileUtils.readFileToByteArray(file), source);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else {
                    long fileSize = event.getFileSize();
                    if (HEADER_FILE_MODIFY == code && fileSize > blockSize) {
                        List<BlockChecksum> blockChecksumList = new LinkedList<>();
                        int blocks = (int) (event.getFileSize() / blockSize);
                        for (int i = 0; i <= blocks; i++) {
                            try {
                                int size = i == blocks ? (int) (fileSize % blockSize) : blockSize;
                                if (0 < size) {
                                    blockChecksumList.add(
                                            new BlockChecksum(i, LocalFileAdaptor.checksum(file, (long) i * blockSize, size)));
                                }
                            } catch (IOException e) {
                                blockChecksumList.clear();
                                break;
                            }
                        }
                        if (!blockChecksumList.isEmpty()) {
                            remoteMessageHandler.sendBlockchecksumList(null, event.getFilePath(), fileSize, blockSize,
                                    blockChecksumList);
                        }
                    } else {
                        int blocks = (int) (event.getFileSize() / blockSize);
                        byte[] data = new byte[blockSize];
                        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                            for (int i = 0; i <= blocks; i++) {
                                if (i == blocks) {
                                    int lastBlockSize = (int) (event.getFileSize() % blockSize);
                                    if (0 != lastBlockSize) {
                                        byte[] lastBlock = new byte[lastBlockSize];
                                        raf.read(lastBlock);
                                        remoteMessageHandler.sendBlock(null, code, event.getFilePath(), event.getFileSize(),
                                                blockSize, i, lastBlock, source);
                                    } else if (0 == blocks) {
                                        remoteMessageHandler.sendBlock(null, code, event.getFilePath(), event.getFileSize(), -1,
                                                0, EMPTY_BYTE, source);
                                    }
                                } else {
                                    raf.seek(i * blockSize);
                                    raf.read(data);
                                    remoteMessageHandler.sendBlock(null, code, event.getFilePath(), event.getFileSize(),
                                            blockSize, i, data, source);
                                }
                            }
                        } catch (FileNotFoundException e) {
                        } catch (IOException e) {
                        }
                    }
                }
            } else {
                remoteMessageHandler.sendEvent(event);
            }
        }

    }

    @Override
    public void cache(FileEvent event) {
        eventQueue.add(event);
        EventHandler observer = echoObserver;
        if (null != observer) {
            // 顺带把"对端写入 + 来源节点"这条信息转给观察者（归档处理器用它把条目记成原始节点）
            observer.cache(event);
        }
    }

    /**
     * 登记一个观察者：协议层每把一条对端写入登记进回环抑制时，同时以 {@code cache(event)} 通知
     * 它一次。归档处理器注册后就能知道"这条路径是哪个节点写的、经过谁转发"。
     *
     * @param echoObserver
     */
    public void setEchoObserver(EventHandler echoObserver) {
        this.echoObserver = echoObserver;
    }
}