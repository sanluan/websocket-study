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
import org.microprofile.file.event.FileEvent;
import org.microprofile.file.event.FileEvent.EventType;
import org.microprofile.file.message.BlockChecksum;

public class FileEventHandler implements EventHandler {
    protected final Log log = LogFactory.getLog(getClass());

    public final static int DEFULT_CACHE_SIZE = 1000;
    private static byte[] EMPTY_BYTE = new byte[0];
    private static byte HEADER_FILE_CREATE = EventType.FILE_CREATE.getCode();
    private static byte HEADER_FILE_MODIFY = EventType.FILE_MODIFY.getCode();

    private EventQueue<FileEvent> eventQueue;
    private LocalFileAdaptor localFileAdaptor;
    private RemoteMessageHandler remoteMessageHandler;

    /**
     * @param localFileHandler
     */
    public FileEventHandler(LocalFileAdaptor localFileHandler) {
        this(localFileHandler, DEFULT_CACHE_SIZE);
    }

    /**
     * @param cacheSize
     * @param localFileAdaptor
     */
    public FileEventHandler(LocalFileAdaptor localFileAdaptor, int cacheSize) {
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
    public FileEventHandler(LocalFileAdaptor localFileAdaptor, SyncMode syncMode) {
        this(localFileAdaptor, DEFULT_CACHE_SIZE, syncMode);
    }

    /**
     * @param localFileAdaptor
     * @param cacheSize
     * @param syncMode
     *            同步方向开关
     */
    public FileEventHandler(LocalFileAdaptor localFileAdaptor, int cacheSize, SyncMode syncMode) {
        this(localFileAdaptor, cacheSize);
        setSyncMode(null == syncMode ? SyncMode.BIDIRECTIONAL : syncMode);
    }

    @Override
    public void handle(FileEvent event) {
        if (!remoteMessageHandler.getSyncMode().isSendEnabled()) {
            log.debug("同步模式 " + remoteMessageHandler.getSyncMode() + " 不允许发送，跳过 " + event);
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
            if (HEADER_FILE_CREATE == code || HEADER_FILE_MODIFY == code) {
                File file = localFileAdaptor.getFile(event.getFilePath());
                int blockSize = localFileAdaptor.getBlockSize();
                if (blockSize > event.getFileSize()) {
                    try {
                        remoteMessageHandler.sendBlock(null, code, event.getFilePath(), event.getFileSize(), -1, 0,
                                FileUtils.readFileToByteArray(file));
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
                                                blockSize, i, lastBlock);
                                    } else if (0 == blocks) {
                                        remoteMessageHandler.sendBlock(null, code, event.getFilePath(), event.getFileSize(), -1,
                                                0, EMPTY_BYTE);
                                    }
                                } else {
                                    raf.seek(i * blockSize);
                                    raf.read(data);
                                    remoteMessageHandler.sendBlock(null, code, event.getFilePath(), event.getFileSize(),
                                            blockSize, i, data);
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

    @Override
    public void cache(FileEvent event) {
        eventQueue.add(event);
    }
}