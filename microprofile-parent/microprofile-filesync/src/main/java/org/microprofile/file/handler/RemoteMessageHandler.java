package org.microprofile.file.handler;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.microprofile.common.constant.Constants;
import org.microprofile.common.utils.EncodeUtils;
import org.microprofile.file.event.EventHandler;
import org.microprofile.file.event.FileEvent;
import org.microprofile.file.event.FileEvent.EventType;
import org.microprofile.file.message.BlockChecksum;
import org.microprofile.file.message.FileChecksum;
import org.microprofile.file.message.FileChecksumResult;
import org.microprofile.websocket.handler.MessageHandler;
import org.microprofile.websocket.handler.Session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public class RemoteMessageHandler implements MessageHandler {
    protected final Log log = LogFactory.getLog(getClass());
    private Map<String, Session> sessionMap = new ConcurrentHashMap<>();
    /**
     * Json Mapper
     */
    public static final ObjectMapper objectMapper = new ObjectMapper();

    private static byte HEADER_FILE_CREATE = EventType.FILE_CREATE.getCode();
    private static byte HEADER_FILE_MODIFY = EventType.FILE_MODIFY.getCode();
    private static byte HEADER_MAX_EVENT = EventType.DIRECTORY_DELETE.getCode();
    private static byte HEADER_FILE_CHECKSUM = 10;
    private static byte HEADER_BLOCK_CHECKSUM = 11;
    private static byte HEADER_FILE_CHECKSUM_RESULT = 12;
    private static byte HEADER_BLOCK_CHECKSUM_RESULT = 13;
    private static byte HEADER_LENGTH = 3;

    private static int LENGTH_SHORT = 2;
    private static int LENGTH_INT = 4;
    private static int LENGTH_LONG = 8;
    private boolean master = false;
    private Lock lock = new ReentrantLock();

    private EventHandler eventHandler;
    private LocalFileAdaptor localFileAdaptor;
    private Map<String, FileBlockChecksumCache> cache = new HashMap<>();
    private ExecutorService pool = Executors.newSingleThreadExecutor(daemonThreadFactory("filesync-reconcile"));

    /**
     * 同步方向开关，默认双向（与原有行为一致）
     */
    private volatile SyncMode syncMode = SyncMode.BIDIRECTIONAL;
    /**
     * 对端写入本地后，抑制“本端变更再次回传”的时间窗（毫秒），应大于本地监听轮询间隔
     */
    private volatile long echoSuppressMillis = 3000L;
    /**
     * 近期由对端写入的文件路径 -> 抑制截止时间
     */
    private final Map<String, Long> recentRemoteWrites = new ConcurrentHashMap<>();
    /**
     * 最近一次收到同步报文的时间，用于第二轮对账的静默判定
     */
    private volatile long lastInboundTime = System.currentTimeMillis();
    /**
     * 第二轮（非 master 端把本端文件树推介给对端）只做一次
     */
    private final AtomicBoolean secondRoundStarted = new AtomicBoolean(false);
    private volatile ScheduledExecutorService reconcileScheduler;
    /**
     * 对端对账静默多久后，本端开始第二轮对账
     */
    private static final long RECONCILE_QUIET_MILLIS = 2000L;
    /**
     * 连接建立后至少等待多久才可能开始第二轮对账（对端首轮对账有 2 秒启动延迟）
     */
    private static final long MIN_PEER_GRACE_MILLIS = 5000L;
    /**
     * 对端一直没有任何推介时，最长再等多久就自行开始第二轮对账
     */
    private static final long NO_PEER_GRACE_MILLIS = 10000L;

    /**
     * @return the sessionMap
     */
    public boolean hasSession() {
        return !sessionMap.isEmpty();
    }

    /**
     * @param eventHandler
     * @param localFileAdaptor
     */
    public RemoteMessageHandler(EventHandler eventHandler, LocalFileAdaptor localFileAdaptor) {
        super();
        this.eventHandler = eventHandler;
        this.localFileAdaptor = localFileAdaptor;
    }

    @Override
    public void onMessage(String message, Session session) throws IOException {
        if (0 < message.length()) {
            handle(message.getBytes(Constants.DEFAULT_CHARSET), session);
        }
    }

    @Override
    public void onMessage(byte[] message, Session session) throws IOException {
        if (0 < message.length) {
            handle(message, session);
        }
    }

    private void handleEvent(byte header, byte[] payload) {
        int filePathLength = readPathLength(payload);
        if (0 > filePathLength) {
            log.warn("文件事件消息头部非法，已丢弃");
            return;
        }
        int startIndex = HEADER_LENGTH + filePathLength;
        if (startIndex <= payload.length) {
            String filePath = new String(Arrays.copyOfRange(payload, HEADER_LENGTH, startIndex), Constants.DEFAULT_CHARSET);
            EventType eventType = null;
            File file;
            switch (header) {
            case 0:
            case 1:
                if (startIndex + LENGTH_LONG + 2 * LENGTH_INT > payload.length) {
                    log.warn(filePath + " 文件块消息长度不足，已丢弃");
                    return;
                }
                long fileSize = EncodeUtils.bype2Long(Arrays.copyOfRange(payload, startIndex, startIndex + LENGTH_LONG));
                startIndex += LENGTH_LONG;
                int blockSize = EncodeUtils.bype2Int(Arrays.copyOfRange(payload, startIndex, startIndex + LENGTH_INT));
                startIndex += LENGTH_INT;
                int blockIndex = EncodeUtils.bype2Int(Arrays.copyOfRange(payload, startIndex, startIndex + LENGTH_INT));
                startIndex += LENGTH_INT;
                if (localFileAdaptor.getFile(filePath).exists()) {
                    eventType = EventType.FILE_MODIFY;
                } else {
                    eventType = EventType.FILE_CREATE;
                }
                if (0 == header && -1 == blockSize) {
                    file = localFileAdaptor.createFile(filePath, payload, startIndex);
                } else {
                    file = localFileAdaptor.modifyFile(filePath, fileSize, blockSize, blockIndex, payload, startIndex);
                }
                break;
            case 2:
                eventType = EventType.FILE_DELETE;
                file = localFileAdaptor.deleteFile(filePath);
                break;
            case 3:
                eventType = EventType.DIRECTORY_CREATE;
                file = localFileAdaptor.createDirectory(filePath);
                break;
            default:
                eventType = EventType.DIRECTORY_DELETE;
                file = localFileAdaptor.deleteDirectory(filePath);
                break;
            }
            if (null != eventType) {
                // 先登记“对端写入”，再交给回环抑制，避免本端监听把这次写入当成用户修改再回传
                markRemoteWrite(filePath);
                eventHandler.cache(new FileEvent(eventType, filePath, file.lastModified(), file.length()));
            }
        }
    }

    private void handleFileChecksum(byte[] payload, Session session) {
        try {
            FileChecksum fileChecksum = objectMapper.readValue(payload, 1, payload.length - 1, FileChecksum.class);
            if (null != fileChecksum && null != fileChecksum.getFilePath()) {
                FileChecksumResult result = null;
                String filePath = fileChecksum.getFilePath();
                File file = localFileAdaptor.getFile(filePath);
                if (fileChecksum.isDirectory()) {
                    if (!file.exists()) {
                        file = localFileAdaptor.createDirectory(filePath);
                        markRemoteWrite(filePath);
                        eventHandler.cache(
                                new FileEvent(EventType.DIRECTORY_CREATE, filePath, file.lastModified(), file.length()));
                    }
                } else {
                    if (file.exists() && !file.isDirectory()) {
                        if (0 < fileChecksum.getFileSize()) {
                            if (fileChecksum.getFileSize() != file.length()) {
                                result = new FileChecksumResult(filePath, true);
                            } else {
                                try {
                                    if (!localFileAdaptor.verifyFile(file, fileChecksum.getChecksum())) {
                                        result = new FileChecksumResult(filePath, true);
                                    }
                                } catch (FileNotFoundException e) {
                                } catch (IOException e) {
                                }
                            }
                        } else if (0 == fileChecksum.getFileSize() && 0 < file.length()) {
                            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                                raf.setLength(0);
                            }
                            // 本地被对端清空，同样要抑制回传，否则两侧会互推空文件
                            markRemoteWrite(filePath);
                            eventHandler.cache(new FileEvent(EventType.FILE_MODIFY, filePath, file.lastModified(), 0));
                        }
                    } else if (!file.exists() && 0 == fileChecksum.getFileSize()) {
                        file.createNewFile();
                        markRemoteWrite(filePath);
                        eventHandler.cache(new FileEvent(EventType.FILE_CREATE, filePath, file.lastModified(), 0));
                    } else {
                        result = new FileChecksumResult(filePath, false);
                    }
                }
                if (null != result) {
                    sendFileChecksumResult(session, result);
                } else {
                    log.debug(fileChecksum.getFilePath() + " are synchronized!");
                }
            }
        } catch (

        IOException e) {
            e.printStackTrace();
        }
    }

    private void handleBlockChecksum(byte[] payload, Session session) {
        int filePathLength = readPathLength(payload);
        if (0 > filePathLength) {
            log.warn("文件块校验消息头部非法，已丢弃");
            return;
        }
        int startIndex = HEADER_LENGTH + filePathLength;
        if (startIndex + LENGTH_LONG + LENGTH_INT < payload.length) {
            String filePath = new String(Arrays.copyOfRange(payload, HEADER_LENGTH, startIndex), Constants.DEFAULT_CHARSET);
            File file = localFileAdaptor.getFile(filePath);
            if (file.exists()) {
                long newFileSize = EncodeUtils.bype2Long(Arrays.copyOfRange(payload, startIndex, startIndex + LENGTH_LONG));
                startIndex += LENGTH_LONG;
                int blockSize = EncodeUtils.bype2Int(Arrays.copyOfRange(payload, startIndex, startIndex + LENGTH_INT));
                startIndex += LENGTH_INT;
                try {
                    List<BlockChecksum> blockChecksumList = objectMapper.readValue(payload, startIndex,
                            payload.length - startIndex, new TypeReference<List<BlockChecksum>>() {
                            });
                    if (null != blockChecksumList) {
                        List<Integer> result = new LinkedList<>();
                        if (file.isDirectory()) {
                            for (BlockChecksum blockChecksum : blockChecksumList) {
                                result.add(blockChecksum.getBlockIndex());
                            }
                            if (!result.isEmpty()) {
                                sendBlockChecksumResultList(session, filePath, newFileSize, blockSize, result);
                            }
                        } else {
                            long fileSize = file.length();
                            for (BlockChecksum blockChecksum : blockChecksumList) {
                                long currentIndex = (long) blockSize * blockChecksum.getBlockIndex();
                                if (fileSize <= currentIndex) {
                                    result.add(blockChecksum.getBlockIndex());
                                } else {
                                    int size = (int) (currentIndex + blockSize > fileSize ? fileSize - currentIndex
                                            : blockSize);
                                    byte[] checksum = LocalFileAdaptor.checksum(file, currentIndex, size);
                                    if (!Arrays.equals(checksum, blockChecksum.getChecksum())) {
                                        result.add(blockChecksum.getBlockIndex());
                                    }
                                }
                            }
                            if (!result.isEmpty()) {
                                sendBlockChecksumResultList(session, filePath, newFileSize, blockSize, result);
                            }
                        }
                    }
                } catch (IOException e) {
                }
            }
        }

    }

    private void handleBlockChecksumResult(byte[] payload, Session session) {
        int filePathLength = readPathLength(payload);
        if (0 > filePathLength) {
            log.warn("文件块校验结果消息头部非法，已丢弃");
            return;
        }
        int startIndex = HEADER_LENGTH + filePathLength;
        if (startIndex + LENGTH_LONG + LENGTH_INT < payload.length) {
            String filePath = new String(Arrays.copyOfRange(payload, HEADER_LENGTH, startIndex), Constants.DEFAULT_CHARSET);
            File file = localFileAdaptor.getFile(filePath);
            long oldFileSize = EncodeUtils.bype2Long(Arrays.copyOfRange(payload, startIndex, startIndex + LENGTH_LONG));
            if (file.exists() && !file.isDirectory() && oldFileSize == file.length()) {
                startIndex += LENGTH_LONG;
                int blockSize = EncodeUtils.bype2Int(Arrays.copyOfRange(payload, startIndex, startIndex + LENGTH_INT));
                startIndex += LENGTH_INT;
                try {
                    List<Integer> blockChecksumResultList = objectMapper.readValue(payload, startIndex,
                            payload.length - startIndex, new TypeReference<List<Integer>>() {
                            });
                    if (null != blockChecksumResultList) {
                        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                            long fileSize = file.length();
                            for (Integer blockIndex : blockChecksumResultList) {
                                long currentIndex = (long) blockSize * blockIndex;
                                int size = (int) (currentIndex + blockSize > fileSize ? fileSize - currentIndex : blockSize);
                                byte[] data = new byte[size];
                                raf.seek(currentIndex);
                                raf.read(data);
                                sendBlock(session, HEADER_FILE_MODIFY, filePath, fileSize, blockSize, blockIndex, data);
                            }
                        } catch (FileNotFoundException e) {
                            e.printStackTrace();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    } else {
                        log.info(filePath + " all blocks are synchronized!");
                    }
                } catch (IOException e) {
                }
            }
        }
    }

    private void handleFileChecksumResult(byte[] payload, Session session) {
        try {
            FileChecksumResult fileChecksumResult = objectMapper.readValue(payload, 1, payload.length - 1,
                    FileChecksumResult.class);
            if (null != fileChecksumResult && null != fileChecksumResult.getFilePath()) {
                String filePath = fileChecksumResult.getFilePath();
                File file = localFileAdaptor.getFile(filePath);
                if (file.exists() && !file.isDirectory()) {
                    int blockSize = localFileAdaptor.getBlockSize();
                    long fileSize = file.length();
                    if (fileChecksumResult.isExists()) {
                        if (blockSize > fileSize) {
                            try {
                                sendBlock(session, HEADER_FILE_MODIFY, filePath, fileSize, -1, 0,
                                        FileUtils.readFileToByteArray(file));
                            } catch (IOException e) {
                            }
                        } else {
                            List<BlockChecksum> blockChecksumList;
                            // 锁必须在 finally 里释放：原来一旦中途抛异常（如文件被占用），
                            // 锁会被永久占住，之后所有文件的对账都会卡死
                            lock.lock();
                            try {
                                FileBlockChecksumCache fileBlockChecksumCache = cache.get(filePath);
                                if (null == fileBlockChecksumCache
                                        || fileBlockChecksumCache.getLastModify() != file.lastModified()) {
                                    blockChecksumList = new LinkedList<>();
                                    int blocks = (int) (fileSize / blockSize);
                                    for (int i = 0; i <= blocks; i++) {
                                        try {
                                            int size = i == blocks ? (int) (fileSize % blockSize) : blockSize;
                                            if (0 < size) {
                                                blockChecksumList.add(new BlockChecksum(i,
                                                        LocalFileAdaptor.checksum(file, (long) i * blockSize, size)));
                                            }
                                        } catch (IOException e) {
                                            blockChecksumList.clear();
                                        }
                                    }
                                    if (null == fileBlockChecksumCache) {
                                        fileBlockChecksumCache = new FileBlockChecksumCache();
                                    }
                                    fileBlockChecksumCache.setBlockChecksumList(blockChecksumList);
                                    fileBlockChecksumCache.setLastModify(file.lastModified());
                                    cache.put(filePath, fileBlockChecksumCache);
                                } else {
                                    blockChecksumList = fileBlockChecksumCache.getBlockChecksumList();
                                }
                            } finally {
                                lock.unlock();
                            }
                            if (!blockChecksumList.isEmpty()) {
                                sendBlockchecksumList(session, filePath, fileSize, blockSize, blockChecksumList);
                            }
                        }
                    } else {
                        int blocks = (int) (file.length() / blockSize);
                        byte[] data = new byte[blockSize];
                        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                            for (int i = 0; i <= blocks; i++) {
                                if (i == blocks) {
                                    int lastBlockSize = (int) (file.length() % blockSize);
                                    if (0 != lastBlockSize) {
                                        byte[] lastBlock = new byte[lastBlockSize];
                                        raf.read(lastBlock);
                                        sendBlock(session, HEADER_FILE_CREATE, filePath, fileSize, blockSize, i, lastBlock);
                                    }
                                } else {
                                    raf.seek((long) i * blockSize);
                                    raf.read(data);
                                    sendBlock(session, HEADER_FILE_CREATE, filePath, fileSize, blockSize, i, data);
                                }
                            }
                        } catch (FileNotFoundException e) {
                            e.printStackTrace();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        } catch (IOException e) {
        }
    }

    public void handle(byte[] payload, Session session) {
        if (null == payload || 0 == payload.length) {
            return;
        }
        byte header = payload[0];
        // 收到任何同步报文都刷新静默计时：第二轮对账要等对端先说
        lastInboundTime = System.currentTimeMillis();
        if (0 <= header && HEADER_MAX_EVENT >= header && HEADER_LENGTH < payload.length) {
            if (!syncMode.isReceiveEnabled()) {
                log.debug("同步模式 " + syncMode + "，忽略对端文件事件\t" + header);
                return;
            }
            log.info("文件消息\t" + header);
            handleEvent(header, payload);
        } else if (HEADER_FILE_CHECKSUM == header) {
            // 对端推介了它的文件树。原来这里限定 !master，导致非 master 端的文件
            // 永远没有机会被对端比较到（双向同步只完成一半）；这里按“能否接收”判断
            if (!syncMode.isReceiveEnabled()) {
                log.debug("同步模式 " + syncMode + "，忽略对端文件树\t" + payload.length);
                return;
            }
            log.info("文件校验消息\t" + payload.length);
            handleFileChecksum(payload, session);
        } else if (HEADER_BLOCK_CHECKSUM == header && HEADER_LENGTH < payload.length) {
            // 对端邀请按块比对（拉取方向的第二步），属于接收方向
            if (!syncMode.isReceiveEnabled()) {
                return;
            }
            log.info("文件块校验消息\t" + payload.length);
            handleBlockChecksum(payload, session);
        } else if (HEADER_FILE_CHECKSUM_RESULT == header) {
            // 对端告知它缺内容，需要本端回送文件块，属于发送方向
            if (!syncMode.isSendEnabled()) {
                log.debug("同步模式 " + syncMode + "，不回送文件内容");
                return;
            }
            log.info("文件校验结果\t" + header);
            handleFileChecksumResult(payload, session);
        } else if (HEADER_BLOCK_CHECKSUM_RESULT == header && HEADER_LENGTH < payload.length) {
            if (!syncMode.isSendEnabled()) {
                return;
            }
            log.info("文件块校验结果\t" + header);
            handleBlockChecksumResult(payload, session);
        }
    }

    @Override
    public void onOpen(Session session) throws IOException {
        sessionMap.put(session.getId(), session);
        log.info(session.getId() + "\t connected!");
        lastInboundTime = System.currentTimeMillis();
        final RemoteMessageHandler remoteMessageHandler = this;
        if (master) {
            if (!syncMode.isSendEnabled()) {
                log.info("同步模式 " + syncMode + " 不允许发送，跳过首轮对账（" + session.getId() + "）");
                return;
            }
            pool.execute(new Runnable() {
                public void run() {
                    try {
                        Thread.sleep(1000 * 2);
                        localFileAdaptor.fileChecksum(session, remoteMessageHandler);
                    } catch (InterruptedException e) {
                    }
                }
            });
        } else if (syncMode.isSendEnabled()) {
            // 首轮由 master 推介它的文件树；本端在它说完并且静默后，
            // 再把本端独有的文件推介出去，双向才算真正对齐
            scheduleSecondRound(session);
        }
    }

    @Override
    public void onClose(Session session) throws IOException {
        sessionMap.remove(session.getId());
        log.info(session.getId() + "\t closed!");
        // 允许下次重连再补一次第二轮对账
        secondRoundStarted.set(false);
    }

    /**
     * 非 master 端的第二轮对账：等对端的首轮对账说完并且静默后，主动推介本端文件树。
     * <p>
     * 只跑一次；若对端尚未连上或本端不在运行，就继续等待。<br>
     * 必须等对端先说完再开始，否则两端同时推介各自的版本，会对同一个文件来回覆盖。
     * </p>
     *
     * @param session
     */
    private void scheduleSecondRound(final Session session) {
        if (!secondRoundStarted.compareAndSet(false, true)) {
            return;
        }
        final long openTime = System.currentTimeMillis();
        final ScheduledFuture<?>[] futureHolder = new ScheduledFuture<?>[1];
        futureHolder[0] = getReconcileScheduler().scheduleWithFixedDelay(new Runnable() {
            public void run() {
                try {
                    if (!hasSession() || !localFileAdaptor.isRunning()) {
                        return;
                    }
                    long now = System.currentTimeMillis();
                    long elapsed = now - openTime;
                    if (MIN_PEER_GRACE_MILLIS > elapsed) {
                        // 对端首轮对账要等 2 秒才开始，先留出启动时间
                        return;
                    }
                    boolean peerSpoke = lastInboundTime > openTime;
                    if (!peerSpoke && NO_PEER_GRACE_MILLIS > elapsed) {
                        // 对端一个文件都没推介（比如它目录为空），再多等一会儿，避免抢先
                        return;
                    }
                    if (RECONCILE_QUIET_MILLIS > now - lastInboundTime) {
                        return;
                    }
                    futureHolder[0].cancel(false);
                    log.info("对端首轮对账已静默，开始第二轮（本端 -> 对端）对账");
                    localFileAdaptor.fileChecksum(null, RemoteMessageHandler.this);
                } catch (Exception e) {
                    log.error("第二轮对账失败", e);
                }
            }
        }, 500L, 500L, TimeUnit.MILLISECONDS);
    }

    private ScheduledExecutorService getReconcileScheduler() {
        if (null == reconcileScheduler) {
            synchronized (this) {
                if (null == reconcileScheduler) {
                    reconcileScheduler = Executors.newSingleThreadScheduledExecutor(
                            daemonThreadFactory("filesync-second-round"));
                }
            }
        }
        return reconcileScheduler;
    }

    private static ThreadFactory daemonThreadFactory(final String name) {
        return new ThreadFactory() {
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, name);
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    /**
     * 登记一次“由对端写入本地”的操作，用于抑制本端监听回调造成的回环推送。
     *
     * @param filePath
     */
    public void markRemoteWrite(String filePath) {
        if (null != filePath && 0 < echoSuppressMillis) {
            if (1024 < recentRemoteWrites.size()) {
                purgeExpiredRemoteWrites();
            }
            recentRemoteWrites.put(filePath, System.currentTimeMillis() + echoSuppressMillis);
        }
    }

    /**
     * @param filePath
     * @return 该路径是否刚被对端写入（是则本端监听产生的事件不应回传）
     */
    public boolean isRemoteWriteEcho(String filePath) {
        if (null == filePath) {
            return false;
        }
        Long expire = recentRemoteWrites.get(filePath);
        if (null == expire) {
            return false;
        }
        if (expire.longValue() <= System.currentTimeMillis()) {
            recentRemoteWrites.remove(filePath);
            return false;
        }
        return true;
    }

    private void purgeExpiredRemoteWrites() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : recentRemoteWrites.entrySet()) {
            if (entry.getValue().longValue() <= now) {
                recentRemoteWrites.remove(entry.getKey());
            }
        }
    }

    private int readPathLength(byte[] payload) {
        if (HEADER_LENGTH > payload.length) {
            return -1;
        }
        short length = EncodeUtils.bype2Short(Arrays.copyOfRange(payload, 1, HEADER_LENGTH));
        return 0 > length ? -1 : length;
    }

    private void send(Session session, byte[] data) {
        if (null == session) {
            for (Session s : sessionMap.values()) {
                try {
                    s.sendByte(data);
                } catch (IOException e) {
                    try {
                        s.close();
                    } catch (IOException e1) {
                    }
                    log.error("can't send to " + s.getId());
                }
            }
        } else {
            try {
                session.sendByte(data);
            } catch (IOException e) {
                try {
                    session.close();
                } catch (IOException e1) {
                }
                log.error("can't send to " + session.getId());
            }
        }
    }

    public void sendEvent(FileEvent event) {
        byte[] pathByte = getFilePathByte(event.getFilePath());
        byte[] payload = new byte[HEADER_LENGTH + pathByte.length];
        setHeader(event.getEventType().getCode(), pathByte, payload);
        send(null, payload);
        log.info("sent " + event);
    }

    public void sendBlock(Session session, byte code, String filePath, long fileSize, int blockSize, int blockIndex,
            byte[] data) {
        byte[] pathByte = filePath.getBytes(Constants.DEFAULT_CHARSET);
        int headerLength = HEADER_LENGTH + pathByte.length;
        byte[] payload = new byte[headerLength + LENGTH_LONG + LENGTH_INT + LENGTH_INT + data.length];

        setHeader(code, pathByte, payload);

        System.arraycopy(EncodeUtils.long2Byte(fileSize), 0, payload, headerLength, LENGTH_LONG);
        headerLength += LENGTH_LONG;
        System.arraycopy(EncodeUtils.int2Byte(blockSize), 0, payload, headerLength, LENGTH_INT);
        headerLength += LENGTH_INT;
        System.arraycopy(EncodeUtils.int2Byte(blockIndex), 0, payload, headerLength, LENGTH_INT);
        headerLength += LENGTH_INT;
        System.arraycopy(data, 0, payload, headerLength, data.length);
        send(session, payload);
        log.info("sent file blocks of:" + filePath + "\t" + data.length);
    }

    public void sendBlockchecksumList(Session session, String filePath, long fileSize, int blockSize,
            List<BlockChecksum> blockList) {
        try {
            sendBlockList(session, HEADER_BLOCK_CHECKSUM, filePath, fileSize, blockSize, blockList);
            log.info("sent file blocks checksum of:" + filePath + "\t" + blockList.size());
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
    }

    private void sendBlockChecksumResultList(Session session, String filePath, long fileSize, int blockSize,
            List<Integer> blockList) {
        try {
            sendBlockList(session, HEADER_BLOCK_CHECKSUM_RESULT, filePath, fileSize, blockSize, blockList);
            log.info("sent file blocks checksum result of:" + filePath + "\t" + blockList.size());
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
    }

    private void sendBlockList(Session session, byte code, String filePath, long fileSize, int blockSize, List<?> blockList)
            throws JsonProcessingException {
        byte[] pathByte = getFilePathByte(filePath);
        int headerLength = HEADER_LENGTH + pathByte.length;
        byte[] data = objectMapper.writeValueAsBytes(blockList);
        byte[] payload = new byte[headerLength + LENGTH_LONG + LENGTH_INT + data.length];

        setHeader(code, pathByte, payload);
        System.arraycopy(EncodeUtils.long2Byte(fileSize), 0, payload, headerLength, LENGTH_LONG);
        headerLength += LENGTH_LONG;
        System.arraycopy(EncodeUtils.int2Byte(blockSize), 0, payload, headerLength, LENGTH_INT);
        headerLength += LENGTH_INT;
        System.arraycopy(data, 0, payload, headerLength, data.length);
        send(session, payload);
    }

    public void sendFileChecksum(Session session, FileChecksum checkSum) {
        try {
            sendFile(session, HEADER_FILE_CHECKSUM, checkSum);
            log.info("sent file checksum list:" + checkSum.getFilePath());
        } catch (JsonProcessingException e) {
        }
    }

    public void sendFileChecksumResult(Session session, FileChecksumResult result) {
        try {
            sendFile(session, HEADER_FILE_CHECKSUM_RESULT, result);
            log.info("sent file checksum result:" + result.getFilePath());
        } catch (JsonProcessingException e) {
        }
    }

    private void sendFile(Session session, byte code, Object object) throws JsonProcessingException {
        byte[] data = objectMapper.writeValueAsBytes(object);
        byte[] payload = new byte[1 + data.length];
        payload[0] = code;
        System.arraycopy(data, 0, payload, 1, data.length);
        if (null == session) {
            send(null, payload);
            return;
        }
        try {
            session.sendByte(payload);
        } catch (IOException e) {
            log.error("can't send to " + session.getId());
        }
    }

    private byte[] getFilePathByte(String filePath) {
        return filePath.getBytes(Constants.DEFAULT_CHARSET);
    }

    /**
     * @return the master
     */
    public boolean isMaster() {
        return master;
    }

    /**
     * @param master
     *            the master to set
     */
    public void setMaster(boolean master) {
        this.master = master;
    }

    /**
     * @return 同步方向开关
     */
    public SyncMode getSyncMode() {
        return syncMode;
    }

    /**
     * 设置同步方向开关：{@link SyncMode#BIDIRECTIONAL}（默认）、{@link SyncMode#PUSH_ONLY}、
     * {@link SyncMode#PULL_ONLY}。
     * <p>
     * 建议在 {@code connect/listen} 之前设置。收到对端报文时按方向立即生效。
     * </p>
     *
     * @param syncMode
     */
    public void setSyncMode(SyncMode syncMode) {
        if (null == syncMode) {
            throw new IllegalArgumentException("syncMode can't be null");
        }
        this.syncMode = syncMode;
        log.info("同步模式设置为 " + syncMode);
    }

    /**
     * @return 回环抑制时间窗（毫秒）
     */
    public long getEchoSuppressMillis() {
        return echoSuppressMillis;
    }

    /**
     * 设置回环抑制时间窗：对端写入本地后，该路径在此时间窗内的本地监听事件不再回传。
     * 取值应大于本地文件监听轮询间隔（{@link org.microprofile.file.listener.FileListener}
     * 默认 1000ms，此处默认 3000ms）。
     *
     * @param echoSuppressMillis
     */
    public void setEchoSuppressMillis(long echoSuppressMillis) {
        if (0 > echoSuppressMillis) {
            throw new IllegalArgumentException("echoSuppressMillis can't be negative");
        }
        this.echoSuppressMillis = echoSuppressMillis;
    }

    private void setHeader(byte code, byte[] pathByte, byte[] payload) {
        payload[0] = code;
        System.arraycopy(EncodeUtils.short2Byte((short) pathByte.length), 0, payload, 1, LENGTH_SHORT);
        System.arraycopy(pathByte, 0, payload, HEADER_LENGTH, pathByte.length);
    }

    private static class FileBlockChecksumCache {
        private List<BlockChecksum> blockChecksumList;
        private long lastModify;

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
         * @return the blockChecksumList
         */
        public List<BlockChecksum> getBlockChecksumList() {
            return blockChecksumList;
        }

        /**
         * @param blockChecksumList
         *            the blockChecksumList to set
         */
        public void setBlockChecksumList(List<BlockChecksum> blockChecksumList) {
            this.blockChecksumList = blockChecksumList;
        }
    }
}
