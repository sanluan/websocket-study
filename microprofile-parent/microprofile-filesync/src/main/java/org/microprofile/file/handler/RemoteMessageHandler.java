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
import org.microprofile.file.archive.DeleteRecorder;
import org.microprofile.file.event.EventHandler;
import org.microprofile.file.event.FileEvent;
import org.microprofile.file.event.FileEvent.EventType;
import org.microprofile.file.event.NodeInfo;
import org.microprofile.file.event.EventSource;
import org.microprofile.file.filter.FileRule;
import org.microprofile.file.filter.FileRuleSet;
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
    private static final byte[] EMPTY_BYTE = new byte[0];
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
     * 多向同步（中继）：一个服务端 + 多个客户端时，把某个客户端发来的变更转发给同一服务端的其它客户端。
     * <p>
     * 默认关闭，保持"每个客户端各自与服务端镜像"的原语义；按需 {@link #setMultiWaySync(boolean)} 打开。
     * </p>
     */
    private volatile boolean multiWaySync = false;
    /**
     * 删除记录器（归档模式用）：把"谁在什么时候删了哪个文件"记下来
     */
    private volatile DeleteRecorder deleteRecorder;
    /**
     * 本节点身份信息（默认启动时采集的本机信息），随事件报文发送出去
     */
    private volatile NodeInfo nodeInfo;
    /**
     * 本端<b>广播</b>规则（白名单/黑名单）：不在范围内的文件不公告、不回送内容、也不转发。
     * 默认 {@link FileRuleSet#EMPTY}，即全部文件。
     */
    private volatile FileRuleSet sendRules = FileRuleSet.EMPTY;
    /**
     * 本端<b>接收</b>规则（白名单/黑名单）：不在范围内的对端变更直接丢弃。
     * 默认 {@link FileRuleSet#EMPTY}，即全部文件。
     */
    private volatile FileRuleSet receiveRules = FileRuleSet.EMPTY;
    /**
     * 路径 -> 最近一次引入内容的来源节点（对账应答时沿用）
     */
    private final Map<String, OriginRecord> origins = new ConcurrentHashMap<>();
    private static final long ORIGIN_TTL_MILLIS = 60000L;
    private static final int MAX_ORIGINS = 10000;
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
     * @return 当前在线会话数（一个服务端接多个客户端时可用来观察连接数）
     */
    public int getSessionCount() {
        return sessionMap.size();
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

    private void handleEvent(byte header, byte[] payload, Session origin) {
        int filePathLength = readPathLength(payload);
        if (0 > filePathLength) {
            log.warn("文件事件消息头部非法，已丢弃");
            return;
        }
        int startIndex = HEADER_LENGTH + filePathLength;
        if (startIndex <= payload.length) {
            String filePath = new String(Arrays.copyOfRange(payload, HEADER_LENGTH, startIndex), Constants.DEFAULT_CHARSET);
            // 路径之后可能带一段"来源信息"：4B 长度 + JSON（谁产生的变更 + 经过哪些节点转发）。
            // 是否带这段由路径长度 short 的最高位标记，不带就是老格式。
            EventSource source = null;
            if (hasSource(payload)) {
                int sourceLength = startIndex + LENGTH_INT <= payload.length
                        ? EncodeUtils.bype2Int(Arrays.copyOfRange(payload, startIndex, startIndex + LENGTH_INT)) : -1;
                if (0 > sourceLength || startIndex + LENGTH_INT + sourceLength > payload.length) {
                    // 标记位说有来源段，却找不到它：报文已损坏，直接丢弃，避免按错误的偏移解析文件内容
                    log.warn(filePath + " 来源信息长度非法，已丢弃");
                    return;
                }
                source = readSource(payload, startIndex + LENGTH_INT, sourceLength);
                startIndex += LENGTH_INT + sourceLength;
            }
            // 内容（事件 0/1 的文件大小、块参数、块数据）从这里开始；事件 2/3/4 没有内容
            int contentIndex = startIndex;
            // 大小规则需要的值：事件 0/1 的报文里带着整文件长度；删除事件没有大小也没有修改时间，
            // 只能按纯文件名规则判定（目录事件不参与规则，始终放行）
            long ruleSize = FileRule.UNSET;
            if ((0 == header || 1 == header) && contentIndex + LENGTH_LONG <= payload.length) {
                ruleSize = EncodeUtils.bype2Long(Arrays.copyOfRange(payload, contentIndex, contentIndex + LENGTH_LONG));
            }
            if (EventType.DIRECTORY_CREATE.getCode() != header && EventType.DIRECTORY_DELETE.getCode() != header
                    && !canReceive(filePath, ruleSize, FileRule.UNSET)) {
                // 接收规则拒绝：不落盘、不记来源、也不中继
                return;
            }
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
                NodeInfo eventSource = null == source ? null : source.getSource();
                if (null != eventSource && null != source && null != source.getRelayers()) {
                    // 让"来源节点"自己也带上转发链，归档条目/删除记录就都看得到“由谁转发”
                    eventSource = eventSource.copy();
                    eventSource.setRelayers(source.getRelayers());
                }
                eventHandler.cache(new FileEvent(eventType, filePath, file.lastModified(), file.length(), eventSource,
                        null == source ? null : source.getRelayers()));
                // 记住这条路径的来源，之后本端给别的节点重发它的内容时沿用
                recordOrigin(filePath, source, eventSource);
                if (EventType.FILE_DELETE == eventType || EventType.DIRECTORY_DELETE == eventType) {
                    DeleteRecorder recorder = deleteRecorder;
                    if (null != recorder) {
                        // 归档模式：删除记录要带上“谁删的”，由报文里的来源信息给出，跨节点转发后仍是原始节点的信息
                        recorder.recordDelete(filePath, eventSource);
                    }
                }
                // 多向同步：把这次变更重新组包转发给其它客户端（排除来源会话），
                // 报文里追加本节点为转发节点，于是 C 收到时看到的是 A 的信息 + "由本节点转发"。
                // 本端的广播规则同样约束中继：被排除的文件不往其它客户端转发。
                if (canSend(filePath, ruleSize, FileRule.UNSET)) {
                    relayEvent(origin, header, filePath, source, Arrays.copyOfRange(payload, contentIndex, payload.length));
                    if (0 == header || 1 == header) {
                        // 转发块内容之后，再给其它客户端一次元数据比对机会：
                        // 若它们本来就和本端不一致（比如版本不同、缺文件），块级差异会被自动补齐
                        relayChecksum(origin, filePath);
                    }
                }
            }
        }
    }

    /**
     * 解析报文里的来源信息
     *
     * @param payload
     * @param offset
     * @param length
     * @return 来源信息；JSON 损坏时返回 null（内容偏移已由调用方自行跳过）
     */
    private EventSource readSource(byte[] payload, int offset, int length) {
        try {
            return objectMapper.readValue(payload, offset, length, EventSource.class);
        } catch (IOException e) {
            log.warn("事件来源信息解析失败，已按无来源处理", e);
            return null;
        }
    }

    /**
     * 记住"这条路径最近一次的内容是哪个节点引入的"。
     * <p>
     * 对账里本端给对端重发文件内容时（12/13 号报文触发的应答），这些内容其实是别人刚同步过来的，
     * 沿用这里的来源，对端就不会把"重发者"当成内容的原始节点。
     * </p>
     *
     * @param filePath
     * @param source
     *            报文里的原始来源信息（可能为 null）
     * @param eventSource
     *            解析出来的来源节点（含转发链）
     */
    private void recordOrigin(String filePath, EventSource source, NodeInfo eventSource) {
        if (null == filePath || null == eventSource) {
            return;
        }
        if (MAX_ORIGINS < origins.size()) {
            origins.clear();
        }
        EventSource context = null == source ? new EventSource() : source.copy();
        context.setSource(eventSource.copy());
        origins.put(filePath, new OriginRecord(context, System.currentTimeMillis()));
    }

    /**
     * @param filePath
     * @return 这条路径内容的来源；没有记录（本端自己的文件）时用本节点身份
     */
    private EventSource originSource(String filePath) {
        OriginRecord record = null == filePath ? null : origins.get(filePath);
        if (null != record && ORIGIN_TTL_MILLIS >= System.currentTimeMillis() - record.getTime()) {
            return record.getSource().copy();
        }
        return new EventSource(getNodeInfo(), null);
    }

    private static class OriginRecord {
        private final EventSource source;
        private final long time;

        OriginRecord(EventSource source, long time) {
            this.source = source;
            this.time = time;
        }

        EventSource getSource() {
            return source;
        }

        long getTime() {
            return time;
        }
    }

    /**
     * @param deleteRecorder
     *            删除记录器（归档模式直接把 {@code ArchiveHandler} 传进来即可）
     */
    public void setDeleteRecorder(DeleteRecorder deleteRecorder) {
        this.deleteRecorder = deleteRecorder;
    }

    /**
     * 多向同步（中继）：把事件报文重新组包转发给同一服务端上的其它会话。
     * <p>
     * 与"原样转发"的区别：报文里带上原始的来源节点信息，并追加本节点到转发链，
     * 因此经过服务端之后，下游客户端拿到的仍然是原始节点的信息，同时知道是"本节点转发"的。
     * </p>
     *
     * @param origin
     *            报文来源会话，转发时排除
     * @param code
     *            事件码
     * @param filePath
     * @param source
     *            原始来源信息（可能为 null）
     * @param content
     *            事件内容（事件 0/1 为文件大小+块参数+块数据，其余为空）
     */
    private void relayEvent(Session origin, byte code, String filePath, EventSource source, byte[] content) {
        if (!canRelay() || null == filePath) {
            return;
        }
        EventSource forwarded = null == source ? new EventSource() : source.copy();
        forwarded.addRelayer(getNodeInfo());
        int forwardedCount = 0;
        for (Session session : sessionMap.values()) {
            if (null != origin && origin.getId().equals(session.getId())) {
                continue;
            }
            send(session, buildPayload(code, filePath, forwarded, content));
            forwardedCount++;
        }
        if (0 < forwardedCount) {
            log.info("relayed " + code + " " + filePath + " (" + forwarded.describe() + ") to " + forwardedCount + " peer(s)");
        }
    }

    /**
     * 多向同步（中继）：报文原样转发（用于不带来源信息的 JSON 报文，如文件校验和）。
     *
     * @param origin
     * @param payload
     */
    private void relayRaw(Session origin, byte[] payload) {
        if (!canRelay()) {
            return;
        }
        int forwarded = 0;
        for (Session session : sessionMap.values()) {
            if (null != origin && origin.getId().equals(session.getId())) {
                continue;
            }
            send(session, payload);
            forwarded++;
        }
        if (0 < forwarded) {
            log.info("relayed " + payload[0] + " to " + forwarded + " peer(s)");
        }
    }

    private boolean canRelay() {
        return multiWaySync && syncMode.isSendEnabled() && 1 < sessionMap.size();
    }

    /**
     * 多向同步：给其它会话补一次文件元数据（不带整文件校验和），让它们各自做块级比对。
     * <p>
     * 只靠转发块内容是不够的：对端如果持有的是另一个版本，收到的几块补丁未必能补齐差异。 补一次元数据后，对端会按需要请求缺的块，最终收敛到本端内容。
     * </p>
     *
     * @param origin
     * @param filePath
     */
    private void relayChecksum(Session origin, String filePath) {
        if (!multiWaySync || !syncMode.isSendEnabled() || 1 >= sessionMap.size()) {
            return;
        }
        if (null == filePath) {
            return;
        }
        File file = localFileAdaptor.getFile(filePath);
        if (!file.exists() || file.isDirectory()) {
            return;
        }
        FileChecksum fileChecksum = new FileChecksum(filePath, false, file.length());
        for (Session session : sessionMap.values()) {
            if (null != origin && origin.getId().equals(session.getId())) {
                continue;
            }
            sendFileChecksum(session, fileChecksum);
        }
    }

    private void handleFileChecksum(byte[] payload, Session session) {
        try {
            FileChecksum fileChecksum = objectMapper.readValue(payload, 1, payload.length - 1, FileChecksum.class);
            if (null != fileChecksum && null != fileChecksum.getFilePath()) {
                FileChecksumResult result = null;
                String filePath = fileChecksum.getFilePath();
                if (!fileChecksum.isDirectory() && !canReceive(filePath, fileChecksum.getFileSize(), FileRule.UNSET)) {
                    // 接收规则排除的文件：对端就算推介了也不比对、不创建、不要求内容
                    return;
                }
                File file = localFileAdaptor.getFile(filePath);
                if (fileChecksum.isDirectory()) {
                    if (!file.exists()) {
                        file = localFileAdaptor.createDirectory(filePath);
                        markRemoteWrite(filePath);
                        eventHandler
                                .cache(new FileEvent(EventType.DIRECTORY_CREATE, filePath, file.lastModified(), file.length()));
                        // 多向同步：让其它客户端也建这个目录
                        relayRaw(session, payload);
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
                            // 多向同步：其它客户端也要跟着清空（受本端广播规则约束）
                            if (canSend(filePath, 0, file.lastModified())) {
                                relayRaw(session, payload);
                            }
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
                if (!canReceive(filePath, newFileSize, FileRule.UNSET)) {
                    // 接收规则排除的文件：不做块级比对，也就不会去要内容
                    return;
                }
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
                                    int size = (int) (currentIndex + blockSize > fileSize ? fileSize - currentIndex : blockSize);
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
            if (!canSend(filePath, 0 > oldFileSize ? oldFileSize : file.length(), file.lastModified())) {
                // 广播规则排除的文件，对端要也不给
                return;
            }
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
                                sendBlock(session, HEADER_FILE_MODIFY, filePath, fileSize, blockSize, blockIndex, data,
                                        originSource(filePath));
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
                if (!canSend(filePath, file.length(), file.lastModified())) {
                    // 广播规则排除的文件，对端缺也不回送
                    return;
                }
                if (file.exists() && !file.isDirectory()) {
                    int blockSize = localFileAdaptor.getBlockSize();
                    long fileSize = file.length();
                    if (fileChecksumResult.isExists()) {
                        if (blockSize > fileSize) {
                            try {
                                sendBlock(session, HEADER_FILE_MODIFY, filePath, fileSize, -1, 0,
                                        FileUtils.readFileToByteArray(file), originSource(filePath));
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
                                        sendBlock(session, HEADER_FILE_CREATE, filePath, fileSize, blockSize, i, lastBlock,
                                                originSource(filePath));
                                    }
                                } else {
                                    raf.seek((long) i * blockSize);
                                    raf.read(data);
                                    sendBlock(session, HEADER_FILE_CREATE, filePath, fileSize, blockSize, i, data,
                                            originSource(filePath));
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
            handleEvent(header, payload, session);
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
                    reconcileScheduler = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("filesync-second-round"));
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

    /**
     * @param payload
     * @return 路径长度；最高位（0x8000）是"带来源信息"标记位，这里屏蔽掉
     */
    private int readPathLength(byte[] payload) {
        if (HEADER_LENGTH > payload.length) {
            return -1;
        }
        short length = EncodeUtils.bype2Short(Arrays.copyOfRange(payload, 1, HEADER_LENGTH));
        return length & 0x7FFF;
    }

    /**
     * @param payload
     * @return 事件报文里是否带来源信息段（路径长度 short 的最高位）
     */
    private boolean hasSource(byte[] payload) {
        if (HEADER_LENGTH > payload.length) {
            return false;
        }
        short length = EncodeUtils.bype2Short(Arrays.copyOfRange(payload, 1, HEADER_LENGTH));
        return 0 != (length & 0x8000);
    }

    /**
     * 组事件报文：{@code [1B 事件码][2B 路径长度(可带来源标记)][路径][4B 来源段长度][来源段 JSON][内容]}
     * <p>
     * 来源段存在时把路径长度 short 的最高位置 1。老版本接收端读到负数长度会判定报文非法并整条丢弃， 是干净失败的降级，不会误解析成别的结构。
     * </p>
     *
     * @param code
     * @param filePath
     * @param source
     *            来源信息，null 表示不带（保持老格式）
     * @param content
     * @return
     */
    private byte[] buildPayload(byte code, String filePath, EventSource source, byte[] content) {
        byte[] pathByte = getFilePathByte(filePath);
        byte[] sourceByte = null;
        if (null != source) {
            try {
                sourceByte = objectMapper.writeValueAsBytes(source);
            } catch (JsonProcessingException e) {
                log.warn("事件来源信息序列化失败，已按无来源发送", e);
            }
        }
        int sourceLength = null == sourceByte ? 0 : LENGTH_INT + sourceByte.length;
        byte[] payload = new byte[HEADER_LENGTH + pathByte.length + sourceLength + content.length];

        payload[0] = code;
        short pathLength = (short) pathByte.length;
        if (0 < sourceLength) {
            pathLength = (short) (pathLength | 0x8000);
        }
        System.arraycopy(EncodeUtils.short2Byte(pathLength), 0, payload, 1, LENGTH_SHORT);
        System.arraycopy(pathByte, 0, payload, HEADER_LENGTH, pathByte.length);

        int index = HEADER_LENGTH + pathByte.length;
        if (0 < sourceLength) {
            System.arraycopy(EncodeUtils.int2Byte(sourceByte.length), 0, payload, index, LENGTH_INT);
            index += LENGTH_INT;
            System.arraycopy(sourceByte, 0, payload, index, sourceByte.length);
            index += sourceByte.length;
        }
        System.arraycopy(content, 0, payload, index, content.length);
        return payload;
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
        EventSource source = null;
        if (null != event.getNodeInfo()) {
            source = new EventSource(event.getNodeInfo(), event.getRelayers());
        }
        byte[] payload = buildPayload(event.getEventType().getCode(), event.getFilePath(), source, EMPTY_BYTE);
        send(null, payload);
        log.info("sent " + event);
    }

    public void sendBlock(Session session, byte code, String filePath, long fileSize, int blockSize, int blockIndex,
            byte[] data) {
        sendBlock(session, code, filePath, fileSize, blockSize, blockIndex, data, new EventSource(getNodeInfo(), null));
    }

    /**
     * 发送文件块（事件 0/1）
     *
     * @param session
     *            目标会话，null 表示所有会话
     * @param code
     *            事件码
     * @param filePath
     *            相对路径
     * @param fileSize
     *            文件总大小
     * @param blockSize
     *            块大小，-1 表示整文件一次发送
     * @param blockIndex
     *            块序号
     * @param data
     *            块数据
     * @param source
     *            这条内容由谁产生（本节点的变更用 {@link #getNodeInfo()}，中继时传原始来源）
     */
    public void sendBlock(Session session, byte code, String filePath, long fileSize, int blockSize, int blockIndex, byte[] data,
            EventSource source) {
        byte[] content = new byte[LENGTH_LONG + LENGTH_INT + LENGTH_INT + data.length];
        int index = 0;
        System.arraycopy(EncodeUtils.long2Byte(fileSize), 0, content, index, LENGTH_LONG);
        index += LENGTH_LONG;
        System.arraycopy(EncodeUtils.int2Byte(blockSize), 0, content, index, LENGTH_INT);
        index += LENGTH_INT;
        System.arraycopy(EncodeUtils.int2Byte(blockIndex), 0, content, index, LENGTH_INT);
        index += LENGTH_INT;
        System.arraycopy(data, 0, content, index, data.length);

        send(session, buildPayload(code, filePath, source, content));
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
     * @return 是否开启多向同步（中继）
     */
    public boolean isMultiWaySync() {
        return multiWaySync;
    }

    /**
     * 打开/关闭多向同步（中继）：一个服务端 + 多个客户端时，服务端把某个客户端发来的变更
     * 转发给同一服务端的其它客户端，文件因此在所有节点上多向同步。
     * <p>
     * 建议只在服务端（{@code master=true} 的一端）打开。关闭时每个客户端各自与服务端镜像， 客户端之间互不可见（原语义）。
     * </p>
     *
     * @param multiWaySync
     */
    public void setMultiWaySync(boolean multiWaySync) {
        this.multiWaySync = multiWaySync;
        log.info("多向同步（中继）" + (multiWaySync ? "已开启" : "已关闭"));
    }

    /**
     * @return 本节点身份信息（默认是启动时采集的本机信息：计算机名、IP、MAC、用户、操作系统等）
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
     * 指定本节点身份信息（默认采集本机信息；容器、多实例、测试时可自定义）
     *
     * @param nodeInfo
     */
    public void setNodeInfo(NodeInfo nodeInfo) {
        this.nodeInfo = nodeInfo;
    }

    /**
     * @return 本端广播（发送）规则
     */
    public FileRuleSet getSendRules() {
        return sendRules;
    }

    /**
     * 设置本端<b>广播</b>规则：不在范围内的文件不公告（包括对账时推介文件树）、不回送内容、也不中继。
     * 传 null 或 {@link FileRuleSet#EMPTY} 表示全部文件都广播。
     *
     * @param sendRules
     */
    public void setSendRules(FileRuleSet sendRules) {
        this.sendRules = null == sendRules ? FileRuleSet.EMPTY : sendRules;
        log.info("发送规则：" + this.sendRules.describe());
    }

    /**
     * @return 本端接收规则
     */
    public FileRuleSet getReceiveRules() {
        return receiveRules;
    }

    /**
     * 设置本端<b>接收</b>规则：不在范围内的对端变更（新建/修改/删除）直接丢弃，不落盘也不转发。
     * 传 null 或 {@link FileRuleSet#EMPTY} 表示接收全部文件。
     *
     * @param receiveRules
     */
    public void setReceiveRules(FileRuleSet receiveRules) {
        this.receiveRules = null == receiveRules ? FileRuleSet.EMPTY : receiveRules;
        log.info("接收规则：" + this.receiveRules.describe());
    }

    /**
     * 本端是否允许把这个文件广播出去
     *
     * @param filePath
     *            相对路径
     * @param fileSize
     *            文件大小，{@link org.microprofile.file.filter.FileRule#UNSET} 表示未知
     * @param lastModified
     *            最后修改时间，{@link org.microprofile.file.filter.FileRule#UNSET} 表示未知
     * @return
     */
    public boolean canSend(String filePath, long fileSize, long lastModified) {
        FileRuleSet rules = sendRules;
        if (rules.matches(filePath, fileSize, lastModified)) {
            return true;
        }
        log.debug("发送规则拒绝：" + filePath + " （" + rules.describe() + "）");
        return false;
    }

    /**
     * 本端是否允许接收这个文件
     *
     * @param filePath
     *            相对路径
     * @param fileSize
     *            文件大小，{@link org.microprofile.file.filter.FileRule#UNSET} 表示未知
     * @param lastModified
     *            最后修改时间，{@link org.microprofile.file.filter.FileRule#UNSET} 表示未知
     * @return
     */
    public boolean canReceive(String filePath, long fileSize, long lastModified) {
        FileRuleSet rules = receiveRules;
        if (rules.matches(filePath, fileSize, lastModified)) {
            return true;
        }
        log.debug("接收规则拒绝：" + filePath + " （" + rules.describe() + "）");
        return false;
    }

    /**
     * @return 回环抑制时间窗（毫秒）
     */
    public long getEchoSuppressMillis() {
        return echoSuppressMillis;
    }

    /**
     * 设置回环抑制时间窗：对端写入本地后，该路径在此时间窗内的本地监听事件不再回传。
     * 取值应大于本地文件监听轮询间隔（{@link org.microprofile.file.listener.FileListener} 默认
     * 1000ms，此处默认 3000ms）。
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
