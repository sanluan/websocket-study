package org.microprofile.file.archive;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.microprofile.common.constant.Constants;
import org.microprofile.file.event.EventHandler;
import org.microprofile.file.event.FileEvent;
import org.microprofile.file.event.NodeInfo;
import org.microprofile.file.handler.LocalFileAdaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 归档模式：把同步目录里的每一次变更按日期记进归档仓库，并可以按任意截止时间合并出当时的全量文件。
 * <p>
 * 落盘规则：
 * <ul>
 * <li>初次同步：{@link #initialFullArchive()} 把同步目录现有文件全部存一份全量（{@code <路径>/<时间>-<序号>.full}）</li>
 * <li>文件新增：存全量快照（大文件同时作为后续块增量的基线）</li>
 * <li>小文件修改（不大于 {@link #getSmallFileThreshold()}）：每次修改存一份带日期的全量快照</li>
 * <li>大文件修改：只存改动过的块（{@code <时间>-<序号>.b<块号>}），基线 + 块增量即可还原</li>
 * <li>删除：写成 {@code <路径>.delete}，每行一条 {@code 删除时间 IP}，只追加不覆盖</li>
 * </ul>
 * 每条归档记录旁边都有一个同名 {@code .json} 元数据（路径、类型、时间、IP、文件长度、块信息）。
 * </p>
 * <p>
 * 用法：
 *
 * <pre>
 * ArchiveHandler archive = new ArchiveHandler(&quot;D:/archive&quot;, listener.getLocalFileAdaptor());
 * listener.addEventHandler(archive);   // 与 FileEventHandler 并列即可，归档不参与收发
 * archive.initialFullArchive();        // 初次同步全量
 * handler.setDeleteRecorder(archive);  // 删除记录带上来源 IP
 * ...
 * archive.export(new File(&quot;D:/export&quot;));                 // 导出当前全量
 * archive.merge(new File(&quot;D:/export&quot;), &quot;2026-11-15&quot;);   // 导出该日期结束时的全量
 * </pre>
 *
 * </p>
 */
public class ArchiveHandler implements EventHandler, DeleteRecorder {

    public final static String SUFFIX_FULL = ".full";
    public final static String SUFFIX_PATCH = ".b";
    public final static String SUFFIX_META = ".json";
    public final static String SUFFIX_DELETE = ".delete";

    /** 不大于该长度的文件，每次修改存全量快照；超过则只存改动的块 */
    public final static int DEFAULT_SMALL_FILE_THRESHOLD = 64 * 1024;
    public final static int DEFAULT_BLOCK_SIZE = 64 * 1024;
    /** 同一个删除动作可能被协议层和监听器各报一次，这个窗口内只记一条 */
    public final static long DEFAULT_DELETE_DEDUPE_MILLIS = 3000L;

    private final static String NAME_TIME_PATTERN = "yyyyMMdd-HHmmss";
    private final static String TEXT_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    private final static Comparator<ArchiveEntry> ENTRY_ORDER = new Comparator<ArchiveEntry>() {
        @Override
        public int compare(ArchiveEntry a, ArchiveEntry b) {
            int result = Long.compare(a.getTime(), b.getTime());
            if (0 != result) {
                return result;
            }
            return Integer.compare(a.getSequence(), b.getSequence());
        }
    };

    protected final Log log = LogFactory.getLog(getClass());

    private final File archiveRoot;
    private final LocalFileAdaptor localFileAdaptor;
    private final ObjectMapperHolder mapper = new ObjectMapperHolder();
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicInteger sequence = new AtomicInteger();

    private volatile int smallFileThreshold = DEFAULT_SMALL_FILE_THRESHOLD;
    private volatile int blockSize = DEFAULT_BLOCK_SIZE;
    private volatile long deleteDedupeMillis = DEFAULT_DELETE_DEDUPE_MILLIS;
    private volatile String localIp;
    /**
     * 本节点身份（默认采集本机信息），归档条目里"谁改的"取它
     */
    private volatile NodeInfo nodeInfo;
    /**
     * 对端写入的来源节点（路径 -> 来源 + 时间），随本地监听报出的事件一起记账
     */
    private final Map<String, SourceRecord> recentSources = new ConcurrentHashMap<>();
    /**
     * 来源信息保留多久（毫秒），应大于本地监听轮询间隔
     */
    private volatile long sourceWindowMillis = DEFAULT_SOURCE_WINDOW_MILLIS;
    private static final long DEFAULT_SOURCE_WINDOW_MILLIS = 3000L;
    private static final int MAX_RECENT_SOURCES = 5000;

    /**
     * @param archiveRoot
     *            归档仓库目录（不能放在同步目录里面）
     * @param localFileAdaptor
     *            同步目录的适配器，用来把相对路径映射回磁盘文件
     */
    public ArchiveHandler(String archiveRoot, LocalFileAdaptor localFileAdaptor) {
        this(new File(archiveRoot), localFileAdaptor);
    }

    /**
     * @param archiveRoot
     * @param localFileAdaptor
     */
    public ArchiveHandler(File archiveRoot, LocalFileAdaptor localFileAdaptor) {
        super();
        if (null == archiveRoot) {
            throw new IllegalArgumentException("archiveRoot is null");
        }
        if (null == localFileAdaptor) {
            throw new IllegalArgumentException("localFileAdaptor is null");
        }
        this.archiveRoot = archiveRoot.getAbsoluteFile();
        this.localFileAdaptor = localFileAdaptor;
        if (0 < localFileAdaptor.getBlockSize()) {
            this.blockSize = localFileAdaptor.getBlockSize();
        }
        File syncRoot = new File(localFileAdaptor.getBasePath()).getAbsoluteFile();
        if (isUnder(this.archiveRoot, syncRoot)) {
            throw new IllegalArgumentException("归档目录不能放在同步目录里面:" + this.archiveRoot);
        }
        if (!this.archiveRoot.isDirectory() && !this.archiveRoot.mkdirs()) {
            throw new IllegalArgumentException("can't create archive root:" + this.archiveRoot);
        }
    }

    // ------------------------------------------------------------------ 事件入口

    @Override
    public void handle(FileEvent event) {
        if (null == event || null == event.getEventType() || null == event.getFilePath()) {
            return;
        }
        String filePath = normalize(event.getFilePath());
        if (filePath.isEmpty()) {
            return;
        }
        // 本地监听的报出的事件带的是本节点身份；若这条路径刚被对端写入（协议层在 cache 里登记过），
        // 就用对端登记的来源节点，于是归档条目里记的也是原始节点 A 而不是本节点 C
        NodeInfo source = takeRecentSource(filePath);
        List<NodeInfo> relayers;
        if (null != source) {
            relayers = source.getRelayers();
        } else {
            source = event.getNodeInfo();
            relayers = event.getRelayers();
        }
        try {
            switch (event.getEventType()) {
            case FILE_CREATE:
                archive(filePath, true, source, relayers);
                break;
            case FILE_MODIFY:
                archive(filePath, false, source, relayers);
                break;
            case FILE_DELETE:
                recordDelete(filePath, source);
                break;
            default:
                // 目录事件不归档：导出时按文件路径自动建目录
                break;
            }
        } catch (IOException e) {
            log.warn("归档失败:" + filePath, e);
        } catch (RuntimeException e) {
            log.warn("归档失败:" + filePath, e);
        }
    }

    /**
     * 登记"对端写入"的来源节点：由协议层在回环抑制登记时调用（见
     * {@code FileEventHandler#setEchoObserver}）。归档本身不做回环抑制，这里只借用这次回调
     * 把来源信息留给随后本地监听报出的事件。
     */
    @Override
    public void cache(FileEvent event) {
        if (null == event || null == event.getFilePath() || null == event.getNodeInfo()) {
            return;
        }
        NodeInfo info = event.getNodeInfo().copy();
        if (null != event.getRelayers()) {
            List<NodeInfo> relayers = new ArrayList<>(event.getRelayers().size());
            for (NodeInfo relayer : event.getRelayers()) {
                if (null != relayer) {
                    relayers.add(relayer.copy());
                }
            }
            info.setRelayers(relayers);
        }
        if (MAX_RECENT_SOURCES < recentSources.size()) {
            recentSources.clear();
        }
        String relPath = normalize(event.getFilePath());
        SourceRecord existing = recentSources.get(relPath);
        if (null != existing && hasRelayers(existing.getNodeInfo()) && !hasRelayers(info)
                && null != existing.getNodeInfo().getNodeId()
                && existing.getNodeInfo().getNodeId().equals(info.getNodeId())) {
            // 同一个来源节点，已有记录带着"由谁转发"，别用后面这条"裸"记录覆盖掉
            return;
        }
        recentSources.put(relPath, new SourceRecord(info, System.currentTimeMillis()));
    }

    private static boolean hasRelayers(NodeInfo info) {
        return null != info && null != info.getRelayers() && !info.getRelayers().isEmpty();
    }

    /**
     * @param relPath
     * @return 时间窗内登记过的来源节点，没有或已过期返回 null
     */
    private NodeInfo takeRecentSource(String relPath) {
        SourceRecord record = recentSources.get(relPath);
        if (null == record) {
            return null;
        }
        if (sourceWindowMillis < System.currentTimeMillis() - record.getTime()) {
            recentSources.remove(relPath);
            return null;
        }
        return record.getNodeInfo();
    }

    /**
     * @return 来源信息时间窗（毫秒）：对端写入后，本地监听多久内的报出事件按对端来源记账
     */
    public long getSourceWindowMillis() {
        return sourceWindowMillis;
    }

    /**
     * @param sourceWindowMillis
     *            应大于本地监听轮询间隔
     */
    public void setSourceWindowMillis(long sourceWindowMillis) {
        this.sourceWindowMillis = sourceWindowMillis;
    }

    private static class SourceRecord {
        private final NodeInfo nodeInfo;
        private final long time;

        SourceRecord(NodeInfo nodeInfo, long time) {
            this.nodeInfo = nodeInfo;
            this.time = time;
        }

        NodeInfo getNodeInfo() {
            return nodeInfo;
        }

        long getTime() {
            return time;
        }
    }

    @Override
    public void recordDelete(String filePath, NodeInfo source) {
        String relPath = normalize(filePath);
        if (relPath.isEmpty()) {
            return;
        }
        lock.lock();
        try {
            File journal = new File(archiveRoot, relPath + SUFFIX_DELETE);
            long now = System.currentTimeMillis();
            String line = format(now) + " " + describeSource(source);
            List<String> lines = journal.isFile()
                    ? Files.readAllLines(journal.toPath(), Constants.DEFAULT_CHARSET) : new ArrayList<String>();
            if (!lines.isEmpty()) {
                String lastLine = lines.get(lines.size() - 1);
                long last = parseTime(lastLine);
                if (0 < last && now - last <= deleteDedupeMillis) {
                    if (!preferSource(lastLine, source)) {
                        // 同一个删除动作已经记过了，保留原有那条
                        return;
                    }
                    // 原有那条只是本节点（本地监听先报的），这次带来的是原始来源节点：替换掉
                    lines.remove(lines.size() - 1);
                }
            }
            FileUtils.forceMkdir(journal.getParentFile());
            StringBuilder content = new StringBuilder();
            for (String kept : lines) {
                content.append(kept).append("\r\n");
            }
            content.append(line).append("\r\n");
            Files.write(journal.toPath(), content.toString().getBytes(Constants.DEFAULT_CHARSET));
            log.info("归档删除记录:" + relPath + " -> " + line);
        } catch (IOException e) {
            log.warn("归档删除记录失败:" + relPath, e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 同一个删除动作被记录下来两遍时（协议层先记了原始来源节点，本地监听随后又记成本节点；
     * 或者反过来），是否用新的这条替换已有的那条。
     *
     * @param existingLine
     *            已记录的那一行
     * @param source
     *            这次要记的来源节点
     * @return 新来源不是本节点、且已有记录里没有它时才替换
     */
    private boolean preferSource(String existingLine, NodeInfo source) {
        if (null == source || null == source.getNodeId()) {
            return false;
        }
        String localNodeId = getNodeInfo().getNodeId();
        if (null != localNodeId && localNodeId.equals(source.getNodeId())) {
            // 这次记的就是本节点，不覆盖已有记录
            return false;
        }
        boolean richerRelayers = null != source.getRelayers() && !source.getRelayers().isEmpty();
        if (existingLine.contains(source.getNodeId())) {
            // 已记的是同一个来源节点：只有这次带着"由谁转发"而旧记录没带时才替换
            return richerRelayers && 0 > existingLine.indexOf("via ");
        }
        return true;
    }

    /**
     * 把来源节点渲染成一行文本：{@code <ip> <计算机名> <节点标识>}，每经过一个转发节点再追加
     * {@code via <ip>}。
     *
     * @param source
     *            来源节点，null 表示未知
     * @return
     */
    private static String describeSource(NodeInfo source) {
        NodeInfo info = null == source ? null : source;
        StringBuilder builder = new StringBuilder();
        if (null == info) {
            builder.append("unknown unknown unknown");
        } else {
            builder.append(emptyToUnknown(info.firstAddress())).append(' ')
                    .append(emptyToUnknown(info.getHostName())).append(' ')
                    .append(emptyToUnknown(info.getNodeId()));
            if (null != info.getRelayers()) {
                for (NodeInfo relayer : info.getRelayers()) {
                    if (null != relayer) {
                        builder.append(" via ").append(emptyToUnknown(relayer.firstAddress()));
                    }
                }
            }
        }
        return builder.toString();
    }

    private static String emptyToUnknown(String text) {
        return null == text || text.isEmpty() ? "unknown" : text;
    }

    // ------------------------------------------------------------------ 初次全量

    /**
     * 初次同步：把同步目录里现有的文件全部归档一份全量快照。
     *
     * @return 新写入的归档条目数
     * @throws IOException
     */
    public int initialFullArchive() throws IOException {
        List<File> files = new ArrayList<>();
        File root = new File(localFileAdaptor.getBasePath());
        if (null == localFileAdaptor.getBasePath() || !root.isDirectory()) {
            log.warn("同步目录不存在，跳过初次全量归档:" + root);
            return 0;
        }
        collectFiles(root, files);
        int written = 0;
        for (File file : files) {
            String relPath = normalize(localFileAdaptor.getRelativeFilePath(file));
            if (relPath.isEmpty()) {
                continue;
            }
            try {
                if (archive(relPath, true)) {
                    written++;
                }
            } catch (IOException e) {
                log.warn("初次全量归档失败:" + relPath, e);
            }
        }
        log.info("初次全量归档完成：文件数=" + files.size() + "，新写入条目=" + written);
        return written;
    }

    // ------------------------------------------------------------------ 合并导出

    /**
     * 导出当前时刻的全量文件。
     *
     * @param targetDir
     *            导出目录
     * @return
     * @throws IOException
     */
    public MergeReport export(File targetDir) throws IOException {
        return merge(targetDir, System.currentTimeMillis());
    }

    /**
     * 合并某个时间点之前的全部修改，导出那一刻的全量文件。
     *
     * @param targetDir
     * @param cutoff
     *            截止时间（毫秒，含该时刻）
     * @return
     * @throws IOException
     */
    public MergeReport merge(File targetDir, long cutoff) throws IOException {
        if (null == targetDir) {
            throw new IllegalArgumentException("targetDir is null");
        }
        File target = targetDir.getAbsoluteFile();
        if (isUnder(target, archiveRoot) || isUnder(archiveRoot, target)) {
            throw new IllegalArgumentException("导出目录不能是归档目录本身或它的子目录:" + target);
        }
        MergeReport report = new MergeReport(cutoff);
        lock.lock();
        try {
            Map<String, List<ArchiveEntry>> byPath = loadAllEntries();
            for (Map.Entry<String, List<ArchiveEntry>> item : byPath.entrySet()) {
                String relPath = item.getKey();
                long lastTime = -1;
                for (ArchiveEntry entry : item.getValue()) {
                    if (cutoff >= entry.getTime()) {
                        lastTime = Math.max(lastTime, entry.getTime());
                    }
                }
                if (0 > lastTime) {
                    // 截止时间点这个文件还不存在
                    continue;
                }
                long deleteTime = lastDeleteTime(relPath, cutoff);
                if (0 < deleteTime && deleteTime >= lastTime) {
                    report.addDeleted(relPath);
                    continue;
                }
                byte[] content = reconstruct(relPath, cutoff);
                if (null == content) {
                    report.addWarning(relPath + " 缺少基线全量，无法重建");
                    continue;
                }
                File file = new File(target, relPath);
                FileUtils.forceMkdir(file.getParentFile());
                Files.write(file.toPath(), content);
                report.addExported(relPath, content.length);
            }
        } finally {
            lock.unlock();
        }
        log.info("归档合并完成 cutoff=" + format(cutoff) + " " + report);
        return report;
    }

    /**
     * 按日期文本合并导出，支持 {@code yyyy-MM-dd}（截止到当天 23:59:59）与
     * {@code yyyy-MM-dd HH:mm:ss} 两种写法。
     *
     * @param targetDir
     * @param timeText
     * @return
     * @throws IOException
     */
    public MergeReport merge(File targetDir, String timeText) throws IOException {
        return merge(targetDir, parseCutoff(timeText));
    }

    // ------------------------------------------------------------------ 归档写入

    /**
     * @param relPath
     * @param forceFull
     *            新建文件必须存全量（同时作为后续块增量的基线）
     * @return 是否真的写了一条件（内容没变时不重复记）
     * @throws IOException
     */
    private boolean archive(String relPath, boolean forceFull) throws IOException {
        return archive(relPath, forceFull, null, null);
    }

    /**
     * @param relPath
     * @param forceFull
     *            新建文件必须存全量（同时作为后续块增量的基线）
     * @param source
     *            产生这次变更的节点（事件里带的来源信息），null 表示未知/本节点
     * @param relayers
     *            转发链
     * @return 是否真的写了一条件（内容没变时不重复记）
     * @throws IOException
     */
    private boolean archive(String relPath, boolean forceFull, NodeInfo source, List<NodeInfo> relayers)
            throws IOException {
        File file = localFileAdaptor.getFile(relPath);
        if (!file.isFile()) {
            return false;
        }
        byte[] current = Files.readAllBytes(file.toPath());
        lock.lock();
        try {
            byte[] latest = reconstruct(relPath, Long.MAX_VALUE);
            if (null != latest && Arrays.equals(latest, current)) {
                // 只是 mtime 变了，内容没动，不重复归档
                return false;
            }
            if (forceFull || null == latest || current.length <= smallFileThreshold) {
                archiveFull(relPath, current, source, relayers);
            } else {
                archivePatch(relPath, current, latest, source, relayers);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    private void archiveFull(String relPath, byte[] content, NodeInfo source, List<NodeInfo> relayers)
            throws IOException {
        ArchiveEntry entry = newEntry(relPath, ArchiveEntry.TYPE_FULL, content.length, source, relayers);
        File base = allocate(relPath, entry);
        writeFile(base.getPath() + SUFFIX_FULL, content);
        writeMeta(base, entry);
        log.info("归档全量:" + relPath + " size=" + content.length + " -> " + entry.getName() + " source=" + entry.getIp());
    }

    private void archivePatch(String relPath, byte[] current, byte[] base, NodeInfo source, List<NodeInfo> relayers)
            throws IOException {
        int size = blockSize();
        int blocks = Math.max(blockCount(current.length, size), blockCount(base.length, size));
        List<BlockRef> changed = new ArrayList<>();
        Map<Integer, byte[]> payloads = new LinkedHashMap<>();
        for (int i = 0; i < blocks; i++) {
            byte[] now = slice(current, i, size);
            byte[] before = slice(base, i, size);
            if (!Arrays.equals(now, before)) {
                changed.add(new BlockRef(i, now.length));
                payloads.put(i, now);
            }
        }
        if (changed.isEmpty() && current.length == base.length) {
            return;
        }
        ArchiveEntry entry = newEntry(relPath, ArchiveEntry.TYPE_PATCH, current.length, source, relayers);
        entry.setBlockSize(size);
        entry.setBlocks(changed);
        File file = allocate(relPath, entry);
        for (Map.Entry<Integer, byte[]> payload : payloads.entrySet()) {
            writeFile(file.getPath() + SUFFIX_PATCH + payload.getKey(), payload.getValue());
        }
        writeMeta(file, entry);
        log.info("归档块增量:" + relPath + " blocks=" + changed.size() + " fileSize=" + current.length);
    }

    private ArchiveEntry newEntry(String relPath, String type, long fileSize, NodeInfo source,
            List<NodeInfo> relayers) {
        long now = System.currentTimeMillis();
        ArchiveEntry entry = new ArchiveEntry();
        entry.setPath(relPath);
        entry.setType(type);
        entry.setTime(now);
        entry.setTimeText(format(now));
        // 条目里记的是"来源节点"，本端自己改的才落到本机地址
        NodeInfo origin = null == source ? getLocalNode() : source;
        entry.setIp(null == origin ? getLocalIp() : nullOrUnknown(origin.firstAddress()));
        if (null != origin) {
            entry.setHostName(origin.getHostName());
            entry.setNodeId(origin.getNodeId());
        }
        List<NodeInfo> chain = null == relayers ? (null == origin ? null : origin.getRelayers()) : relayers;
        if (null != chain && !chain.isEmpty()) {
            List<String> texts = new ArrayList<>(chain.size());
            for (NodeInfo relayer : chain) {
                if (null != relayer) {
                    texts.add(nullOrUnknown(relayer.firstAddress()));
                }
            }
            entry.setRelayers(texts);
        }
        entry.setFileSize(fileSize);
        entry.setBlockSize(blockSize());
        return entry;
    }

    private static String nullOrUnknown(String text) {
        return null == text || text.isEmpty() ? "unknown" : text;
    }

    /**
     * 分配一个未被占用的归档条目名：{@code <yyyyMMdd-HHmmss>-<6 位序号>}。
     */
    private File allocate(String relPath, ArchiveEntry entry) throws IOException {
        File dir = new File(archiveRoot, relPath);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("can't create archive dir:" + dir);
        }
        String timeText = format(entry.getTime(), NAME_TIME_PATTERN);
        int seq = sequence.incrementAndGet();
        String name;
        File meta;
        do {
            name = timeText + "-" + String.format("%06d", Math.abs(seq % 1000000));
            meta = new File(dir, name + SUFFIX_META);
            seq++;
        } while (meta.exists());
        entry.setSequence(seq - 1);
        entry.setName(name);
        return new File(dir, name);
    }

    private void writeMeta(File base, ArchiveEntry entry) throws IOException {
        mapper.write(new File(base.getPath() + SUFFIX_META), entry);
    }

    private void writeFile(String path, byte[] data) throws IOException {
        File file = new File(path);
        FileUtils.forceMkdir(file.getParentFile());
        Files.write(file.toPath(), data);
    }

    // ------------------------------------------------------------------ 重建

    /**
     * 把某条路径在截止时间点的内容重建出来。
     *
     * @return 重建结果；没有任何基线全量时返回 null
     */
    private byte[] reconstruct(String relPath, long cutoff) throws IOException {
        List<ArchiveEntry> entries = readEntries(relPath);
        byte[] content = null;
        for (ArchiveEntry entry : entries) {
            if (cutoff < entry.getTime()) {
                break;
            }
            if (ArchiveEntry.TYPE_FULL.equals(entry.getType())) {
                content = readContent(relPath, entry, SUFFIX_FULL);
            } else if (null != content) {
                content = applyPatch(content, relPath, entry);
            }
        }
        return content;
    }

    private byte[] applyPatch(byte[] base, String relPath, ArchiveEntry entry) throws IOException {
        int size = 0 < entry.getBlockSize() ? entry.getBlockSize() : blockSize();
        byte[] content = base;
        List<BlockRef> blocks = entry.getBlocks();
        if (null != blocks) {
            for (BlockRef block : blocks) {
                byte[] data = readContent(relPath, entry, SUFFIX_PATCH + block.getIndex());
                int offset = block.getIndex() * size;
                if (content.length < offset + data.length) {
                    content = Arrays.copyOf(content, offset + data.length);
                }
                System.arraycopy(data, 0, content, offset, data.length);
            }
        }
        int fileSize = (int) Math.max(0, entry.getFileSize());
        if (content.length != fileSize) {
            // 截断（文件变短）或补齐（文件变长）
            content = Arrays.copyOf(content, fileSize);
        }
        return content;
    }

    private byte[] readContent(String relPath, ArchiveEntry entry, String suffix) throws IOException {
        File file = new File(new File(archiveRoot, relPath), entry.getName() + suffix);
        if (!file.isFile()) {
            log.warn("归档内容缺失:" + file);
            return new byte[0];
        }
        return Files.readAllBytes(file.toPath());
    }

    private List<ArchiveEntry> readEntries(String relPath) {
        File dir = new File(archiveRoot, relPath);
        if (!dir.isDirectory()) {
            return Collections.emptyList();
        }
        File[] files = dir.listFiles();
        if (null == files) {
            return Collections.emptyList();
        }
        List<ArchiveEntry> entries = new ArrayList<>();
        for (File file : files) {
            if (!file.getName().endsWith(SUFFIX_META)) {
                continue;
            }
            try {
                ArchiveEntry entry = mapper.read(file);
                if (null == entry) {
                    continue;
                }
                if (null == entry.getName()) {
                    entry.setName(file.getName().substring(0, file.getName().length() - SUFFIX_META.length()));
                }
                if (null == entry.getPath()) {
                    entry.setPath(relPath);
                }
                entries.add(entry);
            } catch (IOException e) {
                log.warn("归档元数据解析失败:" + file, e);
            }
        }
        Collections.sort(entries, ENTRY_ORDER);
        return entries;
    }

    // ------------------------------------------------------------------ 查询

    /**
     * @return 归档仓库根目录
     */
    public File getArchiveRoot() {
        return archiveRoot;
    }

    /**
     * @param relPath
     * @return 该路径在归档里的条目数（全量 + 块增量）
     */
    public int getEntryCount(String relPath) {
        return readEntries(normalize(relPath)).size();
    }

    /**
     * @param relPath
     * @return 该路径的删除记录条数
     */
    public int getDeleteCount(String relPath) {
        File journal = new File(archiveRoot, normalize(relPath) + SUFFIX_DELETE);
        if (!journal.isFile()) {
            return 0;
        }
        try {
            return Files.readAllLines(journal.toPath(), Constants.DEFAULT_CHARSET).size();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * @param relPath
     * @return 该路径最近一次归档的条目（没有则 null）
     */
    public ArchiveEntry getLatestEntry(String relPath) {
        List<ArchiveEntry> entries = readEntries(normalize(relPath));
        return entries.isEmpty() ? null : entries.get(entries.size() - 1);
    }

    // ------------------------------------------------------------------ 参数

    /**
     * @return 小文件阈值（字节）
     */
    public int getSmallFileThreshold() {
        return smallFileThreshold;
    }

    /**
     * @param smallFileThreshold
     *            不大于该长度的文件每次修改存全量快照
     */
    public void setSmallFileThreshold(int smallFileThreshold) {
        this.smallFileThreshold = smallFileThreshold;
    }

    /**
     * @return the blockSize
     */
    public int getBlockSize() {
        return blockSize;
    }

    /**
     * @param blockSize
     *            大文件块增量的块大小
     */
    public void setBlockSize(int blockSize) {
        this.blockSize = blockSize;
    }

    /**
     * @return the deleteDedupeMillis
     */
    public long getDeleteDedupeMillis() {
        return deleteDedupeMillis;
    }

    /**
     * @param deleteDedupeMillis
     *            同一个删除动作在该窗口内只记一条
     */
    public void setDeleteDedupeMillis(long deleteDedupeMillis) {
        this.deleteDedupeMillis = deleteDedupeMillis;
    }

    // ------------------------------------------------------------------ 内部工具

    private int blockSize() {
        return 0 < blockSize ? blockSize : DEFAULT_BLOCK_SIZE;
    }

    private static int blockCount(int length, int blockSize) {
        return (length + blockSize - 1) / blockSize;
    }

    private static byte[] slice(byte[] data, int index, int blockSize) {
        int offset = index * blockSize;
        if (offset >= data.length) {
            return new byte[0];
        }
        int end = Math.min(offset + blockSize, data.length);
        return Arrays.copyOfRange(data, offset, end);
    }

    private Map<String, List<ArchiveEntry>> loadAllEntries() {
        Map<String, List<ArchiveEntry>> byPath = new LinkedHashMap<>();
        List<File> metas = new ArrayList<>();
        collectMetas(archiveRoot, metas);
        for (File meta : metas) {
            try {
                ArchiveEntry entry = mapper.read(meta);
                if (null == entry || null == entry.getPath()) {
                    continue;
                }
                List<ArchiveEntry> list = byPath.get(entry.getPath());
                if (null == list) {
                    list = new ArrayList<>();
                    byPath.put(entry.getPath(), list);
                }
                list.add(entry);
            } catch (IOException e) {
                log.warn("归档元数据解析失败:" + meta, e);
            }
        }
        for (List<ArchiveEntry> entries : byPath.values()) {
            Collections.sort(entries, ENTRY_ORDER);
        }
        return byPath;
    }

    private static void collectMetas(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (null == files) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                collectMetas(file, out);
            } else if (file.getName().endsWith(SUFFIX_META)) {
                out.add(file);
            }
        }
    }

    private static void collectFiles(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (null == files) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                collectFiles(file, out);
            } else {
                out.add(file);
            }
        }
    }

    private long lastDeleteTime(String relPath, long cutoff) {
        File journal = new File(archiveRoot, relPath + SUFFIX_DELETE);
        if (!journal.isFile()) {
            return 0;
        }
        long result = 0;
        try {
            List<String> lines = Files.readAllLines(journal.toPath(), Constants.DEFAULT_CHARSET);
            for (String line : lines) {
                long time = parseTime(line);
                if (0 < time && cutoff >= time) {
                    result = Math.max(result, time);
                }
            }
        } catch (IOException e) {
            log.warn("删除记录读取失败:" + journal, e);
        }
        return result;
    }

    private static boolean isUnder(File child, File parent) {
        String childPath = child.getAbsolutePath();
        String parentPath = parent.getAbsolutePath();
        if (childPath.equalsIgnoreCase(parentPath)) {
            return true;
        }
        return childPath.toLowerCase().startsWith(parentPath.toLowerCase() + File.separator);
    }

    private String getLocalIp() {
        if (null == localIp) {
            String ip = "127.0.0.1";
            try {
                InetAddress address = InetAddress.getLocalHost();
                if (null != address && null != address.getHostAddress()) {
                    ip = address.getHostAddress();
                }
            } catch (UnknownHostException e) {
                log.debug("取本机地址失败，使用 127.0.0.1", e);
            }
            localIp = ip;
        }
        return localIp;
    }

    /**
     * @return 本节点身份（默认启动时采集的本机信息：计算机名、IP、MAC、用户、操作系统等）
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
     * 指定本节点身份（容器、多实例、测试时用；默认采集本机信息）
     *
     * @param nodeInfo
     */
    public void setNodeInfo(NodeInfo nodeInfo) {
        this.nodeInfo = nodeInfo;
    }

    private NodeInfo getLocalNode() {
        return getNodeInfo();
    }

    /**
     * @param filePath
     * @return 统一用 {@code /} 的相对路径
     */
    static String normalize(String filePath) {
        String path = filePath.replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path;
    }

    private static String format(long millis) {
        return format(millis, TEXT_TIME_PATTERN);
    }

    private static String format(long millis, String pattern) {
        return new SimpleDateFormat(pattern).format(new Date(millis));
    }

    private static long parseTime(String line) {
        if (null == line || 19 > line.length()) {
            return 0;
        }
        try {
            return new SimpleDateFormat(TEXT_TIME_PATTERN).parse(line.substring(0, 19)).getTime();
        } catch (ParseException e) {
            return 0;
        }
    }

    private static long parseCutoff(String timeText) {
        if (null == timeText || timeText.trim().isEmpty()) {
            return System.currentTimeMillis();
        }
        String text = timeText.trim();
        try {
            if (10 == text.length()) {
                // 只给日期：截止到当天 23:59:59
                return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(text + " 23:59:59").getTime();
            }
            return new SimpleDateFormat(TEXT_TIME_PATTERN).parse(text).getTime();
        } catch (ParseException e) {
            throw new IllegalArgumentException("归档合并时间格式应为 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss:" + timeText, e);
        }
    }

    /**
     * 隔离 Jackson 依赖，避免归档处理器的公开 API 出现 ObjectMapper。
     */
    private static class ObjectMapperHolder {
        private final ObjectMapper mapper = new ObjectMapper();

        private void write(File file, Object value) throws IOException {
            mapper.writeValue(file, value);
        }

        private ArchiveEntry read(File file) throws IOException {
            return mapper.readValue(file, ArchiveEntry.class);
        }
    }
}
