package org.microprofile.test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.microprofile.file.archive.ArchiveEntry;
import org.microprofile.file.archive.MergeReport;
import org.microprofile.file.client.FileSyncClient;
import org.microprofile.file.event.EventHandler;
import org.microprofile.file.event.FileEvent;
import org.microprofile.file.event.NodeInfo;
import org.microprofile.file.filter.FileRule;
import org.microprofile.file.filter.FileRuleSet;
import org.microprofile.file.handler.ArchiveEventHandler;
import org.microprofile.file.handler.FileSyncEventHandler;
import org.microprofile.file.handler.LoggingEventHandler;
import org.microprofile.file.handler.RemoteMessageHandler;
import org.microprofile.file.handler.SyncMode;
import org.microprofile.file.listener.FileListener;
import org.microprofile.websocket.WebSocketClient;
import org.microprofile.websocket.WebSocketServer;

/**
 * filesync 端到端自检：
 * <ol>
 * <li>双向同步</li>
 * <li>单向推送 PUSH_ONLY</li>
 * <li>单向拉取 PULL_ONLY</li>
 * <li>一个服务端 + 两个客户端的多向同步（服务端中继）</li>
 * <li>多向同步关闭时客户端之间互不可见</li>
 * <li>LoggingEventHandler 事件日志处理器</li>
 * </ol>
 */
public class FileSyncSelfTest {

    private static final int BLOCK = 64 * 1024;
    private static final int PORT_BIDIR = 20001;
    private static final int PORT_PUSH = 20002;
    private static final int PORT_PULL = 20003;
    private static final int PORT_MULTI = 20004;
    private static final int PORT_MULTI_OFF = 20005;
    private static final int PORT_LOG = 20006;
    private static final int PORT_ARCHIVE = 20007;
    private static final int PORT_NODE = 20008;
    private static final int PORT_RULE = 20009;
    private static final int PORT_RECONNECT = 20010;

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        String base = 0 < args.length ? args[0]
                : System.getProperty("java.io.tmpdir") + File.separator + "filesync-selftest";
        File root = new File(base);
        FileUtils.deleteQuietly(root);
        if (!root.mkdirs()) {
            throw new IllegalStateException("can't create " + root);
        }
        try {
            testBidirectional(new File(root, "bidir"));
            testPushOnly(new File(root, "push"));
            testPullOnly(new File(root, "pull"));
            testMultiWay(new File(root, "multiway"));
            testMultiWayOff(new File(root, "multiway-off"));
            testLoggingHandler(new File(root, "logging"));
            testArchive(new File(root, "archive"));
            testArchiveWithSync(new File(root, "archive-sync"));
            testNodeInfo();
            testEventCarriesNodeInfo(new File(root, "node-event"));
            testNodeChain(new File(root, "node-chain"));
            testRules();
            testRuleFilter(new File(root, "rules"));
            testReconnect(new File(root, "reconnect"));
        } finally {
            System.out.println("RESULT: " + (0 == failed ? "PASS" : "FAIL") + " (" + passed + " passed, " + failed
                    + " failed)");
        }
        System.exit(0 == failed ? 0 : 1);
    }

    // ------------------------------------------------------------------ 双向

    private static void testBidirectional(File root) throws Exception {
        System.out.println("=== BIDIRECTIONAL ===");
        File dirA = new File(root, "a");
        File dirB = new File(root, "b");

        // A 侧准备：小文件、嵌套目录、多块大文件、空文件、与 B 同长度不同内容的分块文件
        write(new File(dirA, "a1.txt"), "hello-from-A".getBytes("UTF-8"));
        write(new File(dirA, "sub/deep.txt"), "nested-in-A".getBytes("UTF-8"));
        write(new File(dirA, "big.bin"), randomBytes(300 * 1024, 11));
        write(new File(dirA, "empty.txt"), new byte[0]);
        byte[] diff = randomBytes(200 * 1024, 12);
        write(new File(dirA, "diff.bin"), diff);

        // B 侧准备：A 没有的文件
        write(new File(dirB, "b1.txt"), "hello-from-B".getBytes("UTF-8"));
        write(new File(dirB, "bsub/only-b.txt"), "only-on-B".getBytes("UTF-8"));
        // 同长度、内容不同：走分块比对（BLOCK_CHECKSUM / BLOCK_CHECKSUM_RESULT）
        byte[] diffB = diff.clone();
        for (int i = 100 * 1024; i < 100 * 1024 + 4096; i++) {
            diffB[i] = (byte) (diffB[i] ^ 0x5A);
        }
        write(new File(dirB, "diff.bin"), diffB);

        Peer server = startServer(dirA, PORT_BIDIR, SyncMode.BIDIRECTIONAL, false);
        Peer client = connectClient(dirB, PORT_BIDIR, SyncMode.BIDIRECTIONAL);
        try {
            waitSessions(server, 1);

            // 首轮：A(master) 推向 B；第二轮：B 静默后把本端独有文件推介给 A
            check("B 收到 A 的小文件", waitEqual(new File(dirA, "a1.txt"), new File(dirB, "a1.txt"), 20000));
            check("B 收到 A 的嵌套文件", waitEqual(new File(dirA, "sub/deep.txt"), new File(dirB, "sub/deep.txt"), 20000));
            check("B 收到 A 的多块大文件", waitEqual(new File(dirA, "big.bin"), new File(dirB, "big.bin"), 30000));
            check("B 收到 A 的空文件", waitEqual(new File(dirA, "empty.txt"), new File(dirB, "empty.txt"), 20000));
            check("A 收到 B 的独有文件", waitEqual(new File(dirB, "b1.txt"), new File(dirA, "b1.txt"), 30000));
            check("A 收到 B 的嵌套文件",
                    waitEqual(new File(dirB, "bsub/only-b.txt"), new File(dirA, "bsub/only-b.txt"), 30000));
            check("同长度不同内容按块对齐", waitEqual(new File(dirA, "diff.bin"), new File(dirB, "diff.bin"), 30000));

            // 实时变更
            write(new File(dirA, "live-a.txt"), "live-from-A".getBytes("UTF-8"));
            check("A 新建文件实时同步到 B", waitEqual(new File(dirA, "live-a.txt"), new File(dirB, "live-a.txt"), 20000));

            write(new File(dirB, "live-b.txt"), "live-from-B".getBytes("UTF-8"));
            check("B 新建文件实时同步到 A", waitEqual(new File(dirB, "live-b.txt"), new File(dirA, "live-b.txt"), 20000));

            write(new File(dirA, "a1.txt"), "hello-from-A-modified".getBytes("UTF-8"));
            check("A 修改文件实时同步到 B", waitEqual(new File(dirA, "a1.txt"), new File(dirB, "a1.txt"), 20000));

            new File(dirA, "newdir").mkdirs();
            check("A 新建目录同步到 B", waitFor(() -> new File(dirB, "newdir").isDirectory(), 20000));

            FileUtils.forceDelete(new File(dirA, "empty.txt"));
            check("A 删除文件同步到 B", waitFor(() -> !new File(dirB, "empty.txt").exists(), 20000));

            // 稳定后再观察一段时间：两侧不应出现反复互相覆盖（回环抖动）
            Thread.sleep(4000);
            long aModify = new File(dirA, "a1.txt").lastModified();
            long bModify = new File(dirB, "a1.txt").lastModified();
            long aBig = new File(dirA, "big.bin").lastModified();
            Thread.sleep(4000);
            check("同步后无回环抖动(a1 mtime 稳定)", aModify == new File(dirA, "a1.txt").lastModified()
                    && bModify == new File(dirB, "a1.txt").lastModified());
            check("同步后无回环抖动(big.bin mtime 稳定)", aBig == new File(dirA, "big.bin").lastModified());
        } finally {
            client.stop();
            server.stop();
        }
    }

    // -------------------------------------------------------------- 单向推送

    private static void testPushOnly(File root) throws Exception {
        System.out.println("=== PUSH_ONLY (客户端只上传) ===");
        File dirA = new File(root, "a");
        File dirB = new File(root, "b");
        write(new File(dirA, "srv.txt"), "server-side".getBytes("UTF-8"));
        write(new File(dirB, "cli.txt"), "client-side".getBytes("UTF-8"));

        Peer server = startServer(dirA, PORT_PUSH, SyncMode.BIDIRECTIONAL, false);
        Peer client = connectClient(dirB, PORT_PUSH, SyncMode.PUSH_ONLY);
        try {
            waitSessions(server, 1);
            check("推送端文件到达接收端", waitEqual(new File(dirB, "cli.txt"), new File(dirA, "cli.txt"), 30000));
            // 给足时间，确认接收端文件没有反向流入推送端
            Thread.sleep(8000);
            check("接收端文件未反向流出", !new File(dirB, "srv.txt").exists());

            write(new File(dirB, "cli-live.txt"), "client-live".getBytes("UTF-8"));
            check("推送端实时变更仍到达接收端",
                    waitEqual(new File(dirB, "cli-live.txt"), new File(dirA, "cli-live.txt"), 20000));

            write(new File(dirA, "srv-live.txt"), "server-live".getBytes("UTF-8"));
            Thread.sleep(8000);
            check("接收端实时变更不会流入推送端", !new File(dirB, "srv-live.txt").exists());
        } finally {
            client.stop();
            server.stop();
        }
    }

    // -------------------------------------------------------------- 单向拉取

    private static void testPullOnly(File root) throws Exception {
        System.out.println("=== PULL_ONLY (客户端只下载) ===");
        File dirA = new File(root, "a");
        File dirB = new File(root, "b");
        write(new File(dirA, "srv.txt"), "server-side".getBytes("UTF-8"));
        write(new File(dirA, "srv-big.bin"), randomBytes(150 * 1024, 21));
        write(new File(dirB, "cli.txt"), "client-side".getBytes("UTF-8"));

        Peer server = startServer(dirA, PORT_PULL, SyncMode.BIDIRECTIONAL, false);
        Peer client = connectClient(dirB, PORT_PULL, SyncMode.PULL_ONLY);
        try {
            waitSessions(server, 1);
            check("拉取端收到对端文件", waitEqual(new File(dirA, "srv.txt"), new File(dirB, "srv.txt"), 30000));
            check("拉取端收到对端大文件",
                    waitEqual(new File(dirA, "srv-big.bin"), new File(dirB, "srv-big.bin"), 30000));

            write(new File(dirB, "cli-live.txt"), "client-live".getBytes("UTF-8"));
            Thread.sleep(8000);
            check("拉取端本地文件不外发", !new File(dirA, "cli.txt").exists() && !new File(dirA, "cli-live.txt").exists());

            write(new File(dirA, "srv-live.txt"), "server-live".getBytes("UTF-8"));
            check("对端实时变更仍可拉取",
                    waitEqual(new File(dirA, "srv-live.txt"), new File(dirB, "srv-live.txt"), 20000));
        } finally {
            client.stop();
            server.stop();
        }
    }

    // ---------------------------------------------------------- 多向同步（中继）

    private static void testMultiWay(File root) throws Exception {
        System.out.println("=== MULTI_WAY (一个服务端 + 两个客户端) ===");
        File dirS = new File(root, "s");
        File dirA = new File(root, "a");
        File dirB = new File(root, "b");

        write(new File(dirS, "s.txt"), "from-server".getBytes("UTF-8"));
        write(new File(dirA, "a.txt"), "from-A".getBytes("UTF-8"));
        write(new File(dirA, "a-big.bin"), randomBytes(200 * 1024, 31));
        write(new File(dirB, "b.txt"), "from-B".getBytes("UTF-8"));
        // 两边同名不同内容：考验冲突后能否收敛
        write(new File(dirA, "shared.txt"), "A-version".getBytes("UTF-8"));
        write(new File(dirB, "shared.txt"), "B-version-longer".getBytes("UTF-8"));

        Peer server = startServer(dirS, PORT_MULTI, SyncMode.BIDIRECTIONAL, true);
        Peer pa = connectClient(dirA, PORT_MULTI, SyncMode.BIDIRECTIONAL);
        Peer pb = connectClient(dirB, PORT_MULTI, SyncMode.BIDIRECTIONAL);
        try {
            waitSessions(server, 2);

            check("服务端收到客户端 A 的文件", waitEqual(new File(dirA, "a.txt"), new File(dirS, "a.txt"), 30000));
            check("客户端 B 通过服务端拿到 A 的文件",
                    waitEqual(new File(dirA, "a.txt"), new File(dirB, "a.txt"), 40000));
            check("服务端收到客户端 B 的文件", waitEqual(new File(dirB, "b.txt"), new File(dirS, "b.txt"), 30000));
            check("客户端 A 通过服务端拿到 B 的文件",
                    waitEqual(new File(dirB, "b.txt"), new File(dirA, "b.txt"), 40000));
            check("服务端文件到达两个客户端",
                    waitEqual(new File(dirS, "s.txt"), new File(dirA, "s.txt"), 30000)
                            && waitEqual(new File(dirS, "s.txt"), new File(dirB, "s.txt"), 30000));
            check("A 的多块大文件经服务端到达 B",
                    waitEqual(new File(dirA, "a-big.bin"), new File(dirS, "a-big.bin"), 40000)
                            && waitEqual(new File(dirA, "a-big.bin"), new File(dirB, "a-big.bin"), 40000));

            // 实时变更：A 新建 -> 服务端 + B
            write(new File(dirA, "live-a.txt"), "live-from-A".getBytes("UTF-8"));
            check("A 新建文件到达服务端",
                    waitEqual(new File(dirA, "live-a.txt"), new File(dirS, "live-a.txt"), 20000));
            check("A 新建文件经服务端中继到 B",
                    waitEqual(new File(dirA, "live-a.txt"), new File(dirB, "live-a.txt"), 20000));

            // B 新建 -> 服务端 + A
            write(new File(dirB, "live-b.txt"), "live-from-B".getBytes("UTF-8"));
            check("B 新建文件经服务端中继到 A",
                    waitEqual(new File(dirB, "live-b.txt"), new File(dirA, "live-b.txt"), 20000));

            // 服务端修改 -> 两个客户端
            write(new File(dirS, "s.txt"), "from-server-modified".getBytes("UTF-8"));
            check("服务端修改到达两个客户端",
                    waitEqual(new File(dirS, "s.txt"), new File(dirA, "s.txt"), 20000)
                            && waitEqual(new File(dirS, "s.txt"), new File(dirB, "s.txt"), 20000));

            // A 删除 -> 服务端 + B
            FileUtils.forceDelete(new File(dirA, "a.txt"));
            check("A 删除文件到达服务端", waitFor(() -> !new File(dirS, "a.txt").exists(), 20000));
            check("A 删除文件中继到 B", waitFor(() -> !new File(dirB, "a.txt").exists(), 20000));

            // 冲突文件最终三端一致且不再抖动
            boolean converged = waitEqual(new File(dirS, "shared.txt"), new File(dirA, "shared.txt"), 40000)
                    && waitEqual(new File(dirS, "shared.txt"), new File(dirB, "shared.txt"), 40000);
            check("同名不同内容最终三端一致", converged);
            Thread.sleep(4000);
            long sharedMtime = new File(dirS, "shared.txt").lastModified();
            long sharedALength = new File(dirA, "shared.txt").length();
            Thread.sleep(4000);
            check("多向同步后无回环抖动", sharedMtime == new File(dirS, "shared.txt").lastModified()
                    && sharedALength == new File(dirA, "shared.txt").length());
        } finally {
            pb.stop();
            pa.stop();
            server.stop();
        }
    }

    private static void testMultiWayOff(File root) throws Exception {
        System.out.println("=== MULTI_WAY OFF (客户端之间互不可见) ===");
        File dirS = new File(root, "s");
        File dirA = new File(root, "a");
        File dirB = new File(root, "b");

        Peer server = startServer(dirS, PORT_MULTI_OFF, SyncMode.BIDIRECTIONAL, false);
        Peer pa = connectClient(dirA, PORT_MULTI_OFF, SyncMode.BIDIRECTIONAL);
        Peer pb = connectClient(dirB, PORT_MULTI_OFF, SyncMode.BIDIRECTIONAL);
        try {
            waitSessions(server, 2);
            // 等两端首轮/第二轮对账都结束，避免后面的文件被整树对账顺带拉走
            Thread.sleep(8000);

            write(new File(dirA, "only-a.txt"), "only-in-A".getBytes("UTF-8"));
            check("关闭中继：A 的变更仍到达服务端",
                    waitEqual(new File(dirA, "only-a.txt"), new File(dirS, "only-a.txt"), 20000));
            Thread.sleep(6000);
            check("关闭中继：B 收不到 A 的变更", !new File(dirB, "only-a.txt").exists());
        } finally {
            pb.stop();
            pa.stop();
            server.stop();
        }
    }

    // -------------------------------------------------------- 日志记录处理器

    private static void testLoggingHandler(File root) throws Exception {
        System.out.println("=== LoggingEventHandler ===");
        File dirA = new File(root, "a");
        File dirB = new File(root, "b");
        write(new File(dirA, "log-srv.txt"), "logging-1".getBytes("UTF-8"));

        LoggingEventHandler serverLog = new LoggingEventHandler("[server]");
        LoggingEventHandler clientLog = new LoggingEventHandler("[client]");

        Peer server = startServer(dirA, PORT_LOG, SyncMode.BIDIRECTIONAL, false, serverLog);
        Peer client = connectClient(dirB, PORT_LOG, SyncMode.BIDIRECTIONAL, clientLog);
        try {
            waitSessions(server, 1);
            check("日志处理器不影响同步", waitEqual(new File(dirA, "log-srv.txt"), new File(dirB, "log-srv.txt"), 30000));

            write(new File(dirA, "log-2.txt"), "logging-2".getBytes("UTF-8"));
            new File(dirA, "log-dir").mkdirs();
            FileUtils.forceDelete(new File(dirA, "log-srv.txt"));

            check("服务端日志处理器记录到本地事件", waitFor(() -> 0 < serverLog.getHandledCount(), 20000));
            check("日志处理器按事件类型计数",
                    waitFor(() -> 0 < serverLog.getHandledCount(FileEvent.EventType.FILE_CREATE)
                            && 0 < serverLog.getHandledCount(FileEvent.EventType.FILE_DELETE), 20000));
            check("客户端日志处理器记录到对端写入引起的事件", waitFor(() -> 2 <= clientLog.getHandledCount(), 20000));
            System.out.println("[info] server summary: " + serverLog.getSummary());
            System.out.println("[info] client summary: " + clientLog.getSummary());
        } finally {
            client.stop();
            server.stop();
        }
    }

    // ------------------------------------------------------------------ 归档

    private static void testArchive(File root) throws Exception {
        System.out.println("=== ARCHIVE (归档模式，本地) ===");
        File syncDir = new File(root, "sync");
        File archiveDir = new File(root, "archive");
        File exportOld = new File(root, "export-old");
        File exportNow = new File(root, "export-now");

        write(new File(syncDir, "keep.txt"), "v1".getBytes("UTF-8"));
        write(new File(syncDir, "sub/x.txt"), "x-v1".getBytes("UTF-8"));
        byte[] big = randomBytes(200 * 1024, 41);
        write(new File(syncDir, "big.bin"), big);

        FileListener listener = new FileListener(syncDir.getAbsolutePath(), BLOCK, TrueFileFilter.INSTANCE);
        ArchiveEventHandler archive = new ArchiveEventHandler(archiveDir, listener.getLocalFileAdaptor());
        listener.addEventHandler(archive);

        int initial = archive.initialFullArchive();
        check("初次同步：全量归档现有文件", 3 == initial && 1 <= archive.getEntryCount("keep.txt"));
        check("初次同步：大文件也存了全量基线", 1 <= archive.getEntryCount("big.bin"));
        check("归档目录结构与文件一致", new File(archiveDir, "sub/x.txt").isDirectory());
        long cutoffOld = System.currentTimeMillis();
        Thread.sleep(1200);

        listener.start();
        try {
            // 小文件修改：按日期存全量快照
            write(new File(syncDir, "keep.txt"), "v2-longer-content".getBytes("UTF-8"));
            check("小文件修改按日期存全量", waitFor(() -> 2 <= archive.getEntryCount("keep.txt"), 20000));
            ArchiveEntry keepEntry = archive.getLatestEntry("keep.txt");
            check("小文件快照是全量条目", null != keepEntry && ArchiveEntry.TYPE_FULL.equals(keepEntry.getType())
                    && 17 == keepEntry.getFileSize());

            // 大文件修改：只存改动过的块
            byte[] big2 = big.clone();
            for (int i = 100 * 1024; i < 100 * 1024 + 4096; i++) {
                big2[i] = (byte) (big2[i] ^ 0x33);
            }
            write(new File(syncDir, "big.bin"), big2);
            check("大文件修改只存改动块", waitFor(() -> {
                ArchiveEntry entry = archive.getLatestEntry("big.bin");
                return null != entry && ArchiveEntry.TYPE_PATCH.equals(entry.getType()) && null != entry.getBlocks()
                        && 1 == entry.getBlocks().size();
            }, 20000));
            ArchiveEntry bigEntry = archive.getLatestEntry("big.bin");
            check("块增量体积远小于整个文件", null != bigEntry && sumBlockBytes(archiveDir, bigEntry) <= BLOCK
                    && sumBlockBytes(archiveDir, bigEntry) < big.length / 3);

            // 删除：写成 <路径>.delete，内容是删除日期 + IP
            FileUtils.forceDelete(new File(syncDir, "sub/x.txt"));
            check("删除写成 .delete 记录", waitFor(() -> 0 < archive.getDeleteCount("sub/x.txt"), 20000));
            String journal = new String(Files.readAllBytes(new File(archiveDir, "sub/x.txt.delete").toPath()), "UTF-8");
            check("删除记录含日期与来源 IP",
                    journal.matches("(?s).*\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d \\S+.*"));

            // 合并：截止到改动之前
            MergeReport old = archive.merge(exportOld, cutoffOld);
            check("按日期合并：旧版本内容还原", "v1".equals(readText(new File(exportOld, "keep.txt"))));
            check("按日期合并：当时还在的文件仍导出", new File(exportOld, "sub/x.txt").isFile()
                    && "x-v1".equals(readText(new File(exportOld, "sub/x.txt"))));
            check("按日期合并：结果统计", 3 == old.getExportedCount() && old.getDeleted().isEmpty());

            // 合并：当前（最新全量）
            MergeReport now = archive.export(exportNow);
            check("导出当前全量：小文件是最新内容",
                    contentEquals(new File(syncDir, "keep.txt"), new File(exportNow, "keep.txt")));
            check("导出当前全量：大文件由基线+块增量精确还原",
                    contentEquals(new File(syncDir, "big.bin"), new File(exportNow, "big.bin")));
            check("导出当前全量：已删除文件不出现", !new File(exportNow, "sub/x.txt").exists()
                    && now.getDeleted().contains("sub/x.txt") && 2 == now.getExportedCount());
            System.out.println("[info] archive now: " + now);

            // 按日期文本合并：yyyy-MM-dd 表示截止当天 23:59:59
            String today = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
            MergeReport byDay = archive.merge(new File(root, "export-day"), today);
            check("按日期文本合并（yyyy-MM-dd）",
                    2 == byDay.getExportedCount() && byDay.getDeleted().contains("sub/x.txt"));
        } finally {
            listener.stop();
        }
    }

    private static void testArchiveWithSync(File root) throws Exception {
        System.out.println("=== ARCHIVE + SYNC (客户端变更归档到服务端) ===");
        File dirS = new File(root, "s");
        File dirC = new File(root, "c");
        File archiveDir = new File(root, "archive");
        File exportDir = new File(root, "export");
        write(new File(dirS, "srv.txt"), "server-side".getBytes("UTF-8"));

        Peer server = startServerWithArchive(dirS, PORT_ARCHIVE, archiveDir);
        Peer client = connectClient(dirC, PORT_ARCHIVE, SyncMode.BIDIRECTIONAL);
        ArchiveEventHandler archive = server.archive;
        try {
            waitSessions(server, 1);
            check("归档模式不影响同步", waitEqual(new File(dirS, "srv.txt"), new File(dirC, "srv.txt"), 30000));

            write(new File(dirC, "from-client.txt"), "client-1".getBytes("UTF-8"));
            check("客户端新增到达服务端",
                    waitEqual(new File(dirC, "from-client.txt"), new File(dirS, "from-client.txt"), 30000));
            check("客户端新增被服务端归档", waitFor(() -> 0 < archive.getEntryCount("from-client.txt"), 25000));

            write(new File(dirC, "from-client.txt"), "client-2-longer".getBytes("UTF-8"));
            check("客户端修改被服务端归档", waitFor(() -> 2 <= archive.getEntryCount("from-client.txt"), 25000));

            FileUtils.forceDelete(new File(dirC, "from-client.txt"));
            check("客户端删除到达服务端", waitFor(() -> !new File(dirS, "from-client.txt").exists(), 25000));
            check("客户端删除被归档", waitFor(() -> 0 < archive.getDeleteCount("from-client.txt"), 25000));
            String journalLine = waitContains(new File(archiveDir, "from-client.txt.delete"), 20000,
                    NodeInfo.local().getNodeId(), NodeInfo.local().getHostName());
            check("删除记录带对端节点信息且只记一条",
                    null != journalLine && 1 == journalLine.split("\\r?\\n").length);
            check("直连删除的删除记录没有转发标记", null != journalLine && journalLine.indexOf("via ") < 0);

            MergeReport report = archive.export(exportDir);
            check("服务端归档可导出当前全量", contentEquals(new File(dirS, "srv.txt"), new File(exportDir, "srv.txt"))
                    && !new File(exportDir, "from-client.txt").exists() && report.getDeleted().contains("from-client.txt"));
        } finally {
            client.stop();
            server.stop();
        }
    }

    // ---------------------------------------------------------------- 节点身份

    /**
     * 节点身份采集：启动时采集本机信息并缓存。
     */
    private static void testNodeInfo() throws Exception {
        System.out.println("=== NODE INFO ===");
        NodeInfo local = NodeInfo.local();
        check("NodeInfo 缓存同一实例", local == NodeInfo.local());
        check("采集到计算机名", null != local.getHostName() && !local.getHostName().isEmpty());
        check("采集到网卡地址", !local.firstAddress().isEmpty());
        check("采集到节点标识", null != local.getNodeId() && local.getNodeId().contains(local.getHostName()));
        check("采集到用户/操作系统/Java 版本",
                null != local.getUserName() && null != local.getOs() && null != local.getJavaVersion());
        check("采集到进程号与启动时间", 0 < local.getPid() && 0 < local.getStartTime());
        System.out.println("  nodeInfo=" + local);
    }

    /**
     * 本地事件必须带上监听器设定的节点信息（离线场景，不需要网络）。
     */
    private static void testEventCarriesNodeInfo(File root) throws Exception {
        System.out.println("=== EVENT CARRIES NODE INFO ===");
        NodeInfo custom = new NodeInfo("node-X", "host-X", "10.9.9.9");
        File dir = new File(root, "dir");
        FileUtils.forceMkdir(dir);
        final List<FileEvent> captured = new ArrayList<>();
        EventHandler recorder = new EventHandler() {
            @Override
            public void handle(FileEvent event) {
                synchronized (captured) {
                    captured.add(event);
                }
            }

            @Override
            public void cache(FileEvent event) {
                // 不做回环抑制
            }
        };
        FileListener listener = new FileListener(dir.getAbsolutePath(), BLOCK, TrueFileFilter.INSTANCE);
        listener.setNodeInfo(custom);
        listener.addEventHandler(recorder);
        listener.start();
        try {
            write(new File(dir, "hello.txt"), "hello".getBytes("UTF-8"));
            boolean handled = waitFor(() -> {
                synchronized (captured) {
                    return !captured.isEmpty();
                }
            }, 20000);
            FileEvent event;
            synchronized (captured) {
                event = captured.isEmpty() ? null : captured.get(0);
            }
            check("本地事件携带监听器设定的节点信息",
                    handled && null != event && "node-X".equals(event.getNodeInfo().getNodeId()));
            check("本地事件来源标签可读（计算机名/首个 IP）",
                    null != event && "host-X/10.9.9.9".equals(event.getSourceLabel()));
            check("本地事件没有转发节点", null != event && event.getSourceLabel().indexOf("via ") < 0);
        } finally {
            listener.stop();
        }
    }

    /**
     * A → S → C 链路：C 的删除记录是 A 的节点信息 + "由 S 转发"；S 自己的记录是 A 但无转发标记。
     */
    private static void testNodeChain(File root) throws Exception {
        System.out.println("=== NODE CHAIN A -> S -> C ===");
        NodeInfo nodeA = new NodeInfo("node-A", "host-A", "10.0.0.1");
        NodeInfo nodeS = new NodeInfo("node-S", "host-S", "10.0.0.2");
        NodeInfo nodeC = new NodeInfo("node-C", "host-C", "10.0.0.3");
        File dirA = new File(root, "a");
        File dirS = new File(root, "s");
        File dirC = new File(root, "c");
        File archiveS = new File(root, "archive-s");
        File archiveC = new File(root, "archive-c");

        Peer server = startNode(dirS, nodeS, true, PORT_NODE, archiveS);
        Peer clientA = startNode(dirA, nodeA, false, PORT_NODE, null);
        Peer clientC = startNode(dirC, nodeC, false, PORT_NODE, archiveC);
        try {
            waitSessions(server, 2);
            write(new File(dirA, "chain.txt"), randomBytes(200, 20261115L));
            check("A 的创建经 S 中继到达 C",
                    waitEqual(new File(dirA, "chain.txt"), new File(dirC, "chain.txt"), 30000));
            check("C 归档到 chain.txt 的最新条目",
                    waitFor(() -> null != clientC.archive.getLatestEntry("chain.txt"), 20000));
            ArchiveEntry entryC = clientC.archive.getLatestEntry("chain.txt");
            check("C 的归档条目记录的是 A 的节点信息", null != entryC && "10.0.0.1".equals(entryC.getIp())
                    && "host-A".equals(entryC.getHostName()) && "node-A".equals(entryC.getNodeId()));
            check("C 的归档条目记录由 S 转发",
                    null != entryC && null != entryC.getRelayers() && entryC.getRelayers().contains("10.0.0.2"));
            check("S 也有 chain.txt 的归档条目",
                    waitFor(() -> null != server.archive.getLatestEntry("chain.txt"), 20000));
            ArchiveEntry entryS = server.archive.getLatestEntry("chain.txt");
            check("S 的归档条目记录的是 A 的节点信息且无转发链", null != entryS && "node-A".equals(entryS.getNodeId())
                    && (null == entryS.getRelayers() || entryS.getRelayers().isEmpty()));
            // 首轮/第二轮对账会互相推一次文件树，等它彻底安静下来，删除动作才不会被"推回来"的文件打断
            waitQuiet(new File(dirA, "chain.txt"), 3000, 40000);

            FileUtils.forceDelete(new File(dirA, "chain.txt"));
            check("删除经 S 中继到达 C", waitFor(() -> !new File(dirC, "chain.txt").exists(), 30000));

            File journalC = new File(archiveC, "chain.txt.delete");
            // 本地监听可能先把删除记成本节点，协议层随后用原始来源节点替换，所以等到带着 A 的信息再断言
            String lineC = waitContains(journalC, 20000, "10.0.0.1", "host-A", "node-A", "via 10.0.0.2");
            check("C 的删除记录是 A 的节点信息并标明由 S 转发", null != lineC);
            check("C 的删除记录只记一条", null != lineC && 1 == lineC.split("\\r?\\n").length);
            check("C 的删除记录没有把本节点当成来源", null != lineC && lineC.indexOf("node-C") < 0);

            File journalS = new File(archiveS, "chain.txt.delete");
            String lineS = waitContains(journalS, 20000, "10.0.0.1", "host-A", "node-A");
            check("S 的删除记录也是 A 的节点信息", null != lineS);
            check("S 的删除记录没有转发标记（A 直连）", null != lineS && lineS.indexOf("via ") < 0);
            System.out.println("  C journal: " + lineC);
            System.out.println("  S journal: " + lineS);
        } finally {
            clientA.stop();
            clientC.stop();
            server.stop();
        }
    }

    // -------------------------------------------------------------- 规则过滤

    /**
     * 规则判定本身（纯内存，不联网）：白名单优先级、通配符、大小、修改日期、默认全放行。
     */
    private static void testRules() throws Exception {
        System.out.println("=== FILE RULES ===");
        long now = System.currentTimeMillis();

        // 没有配置任何规则 = 全部文件
        check("默认规则（未配置）全部放行", FileRuleSet.EMPTY.matches("a/b/c.txt", 100, now));

        // 白名单：只有命中才放行
        FileRuleSet white = FileRuleSet.ofWhitelist(FileRule.ofPattern("*.txt"));
        check("白名单放行命中项", white.matches("a.txt", 100, now));
        check("白名单拒绝未命中项", !white.matches("a.tmp", 100, now));

        // 黑名单：命中即排除，其余放行
        FileRuleSet black = FileRuleSet.ofBlacklist(FileRule.ofPattern("*.tmp"));
        check("黑名单排除命中项", !black.matches("x.tmp", 100, now));
        check("黑名单不影响其它文件", black.matches("x.txt", 100, now));

        // 同一条路径同时命中白名单与黑名单：白名单优先（放行）
        FileRuleSet both = FileRuleSet.ofBlacklist(FileRule.ofPattern("*.tmp"))
                .withWhitelist(FileRule.ofPattern("keep*.tmp"));
        check("白名单优先级高于黑名单（同时命中时放行）", both.matches("keep.tmp", 100, now) && !both.matches("drop.tmp", 100,
                now));

        // 通配符：** 跨目录，* 不跨目录
        FileRuleSet deep = FileRuleSet.ofBlacklist(FileRule.ofPattern("logs/**/*.log"));
        check("通配符 ** 跨目录", !deep.matches("logs/a.log", 100, now) && !deep.matches("logs/2026/11/b.log", 100, now)
                && deep.matches("data/c.log", 100, now));

        // 大小规则（含边界）
        FileRuleSet small = FileRuleSet.ofWhitelist(FileRule.builder().pattern("*.bin").maxSize(1024 * 1024).build());
        check("大小上限规则", small.matches("a.bin", 1024 * 1024, now) && !small.matches("b.bin", 1024 * 1024 + 1, now));
        FileRuleSet big = FileRuleSet.ofWhitelist(FileRule.builder().pattern("*.bin").minSize(1024).build());
        check("大小下限规则", big.matches("a.bin", 2048, now) && !big.matches("b.bin", 512, now));

        // 修改日期规则
        FileRuleSet old = FileRuleSet.ofBlacklist(FileRule.modifiedBefore(now - 86400000L));
        check("修改日期规则", !old.matches("old.txt", 100, now - 2 * 86400000L) && old.matches("new.txt", 100, now));

        // 大小/时间未知（删除事件就是这样）：带大小/日期条件的规则不命中
        check("大小未知时带大小条件的白名单不放行", !big.matches("a.bin", FileRule.UNSET, now));
        check("修改时间未知时黑名单的日期规则不生效（放行）", old.matches("x.txt", 100, FileRule.UNSET));

        // regex: 前缀按正则表达式处理
        FileRuleSet regex = FileRuleSet.ofBlacklist(FileRule.ofRegex(".*\\.(tmp|bak)"));
        check("regex: 前缀按正则处理", !regex.matches("a.tmp", 100, now) && !regex.matches("b.bak", 100, now)
                && regex.matches("c.txt", 100, now));

        check("规则描述可读", 0 <= both.describe().indexOf("keep*.tmp"));
    }

    /**
     * 每一端配置自己的广播/接收白名单黑名单，验证真正生效：被排除的文件既不出去、也进不来。
     */
    private static void testRuleFilter(File root) throws Exception {
        System.out.println("=== RULES ON THE WIRE ===");
        File dirS = new File(root, "s");
        File dirC = new File(root, "c");

        Peer server = startServer(dirS, PORT_RULE, SyncMode.BIDIRECTIONAL, false);
        // 服务端：广播时排除 .tmp；接收只收 .txt
        server.handler.setSendRules(FileRuleSet.ofBlacklist(FileRule.ofPattern("*.tmp")));
        server.handler.setReceiveRules(FileRuleSet.ofWhitelist(FileRule.ofPattern("*.txt")));

        Peer client = connectClient(dirC, PORT_RULE, SyncMode.BIDIRECTIONAL);
        // 客户端：广播时排除 .bak；接收不接受 .log
        client.handler.setSendRules(FileRuleSet.ofBlacklist(FileRule.ofPattern("*.bak")));
        client.handler.setReceiveRules(FileRuleSet.ofBlacklist(FileRule.ofPattern("*.log")));
        try {
            waitSessions(server, 1);

            // 1) 客户端广播被允许的 .txt → 服务端（接收白名单命中）
            write(new File(dirC, "from-c.txt"), "allow-from-c".getBytes("UTF-8"));
            check("服务端收到客户端允许的 from-c.txt",
                    null != waitContains(new File(dirS, "from-c.txt"), 20000, "allow-from-c"));

            // 2) 客户端广播 .tmp → 服务端接收白名单拒绝
            write(new File(dirC, "from-c.tmp"), "deny-by-receive-white".getBytes("UTF-8"));
            Thread.sleep(3000);
            check("服务端没有接收被接收白名单排除的 from-c.tmp", !new File(dirS, "from-c.tmp").exists());

            // 3) 客户端自己的广播黑名单拦下 .bak（连对账公告都不会发出去）
            write(new File(dirC, "x.bak"), "deny-by-send-black".getBytes("UTF-8"));
            Thread.sleep(3000);
            check("客户端没有广播被发送黑名单排除的 x.bak", !new File(dirS, "x.bak").exists());

            // 4) 服务端 .txt → 客户端（两边都允许）
            write(new File(dirS, "from-s.txt"), "allow-from-s".getBytes("UTF-8"));
            check("客户端收到服务端允许的 from-s.txt",
                    null != waitContains(new File(dirC, "from-s.txt"), 20000, "allow-from-s"));

            // 5) 服务端 .log → 客户端接收黑名单拒绝（服务端发送规则并不排除它）
            write(new File(dirS, "from-s.log"), "deny-by-receive-black".getBytes("UTF-8"));
            Thread.sleep(3000);
            check("客户端没有接收被接收黑名单排除的 from-s.log", !new File(dirC, "from-s.log").exists());

            // 6) 服务端 .tmp → 服务端广播黑名单拦下（客户端规则与此无关）
            write(new File(dirS, "from-s.tmp"), "deny-by-send-black-s".getBytes("UTF-8"));
            Thread.sleep(3000);
            check("服务端没有广播被发送黑名单排除的 from-s.tmp", !new File(dirC, "from-s.tmp").exists());

            System.out.println("  server rules: " + server.handler.getSendRules().describe() + " / "
                    + server.handler.getReceiveRules().describe());
            System.out.println("  client rules: " + client.handler.getSendRules().describe() + " / "
                    + client.handler.getReceiveRules().describe());
        } finally {
            client.stop();
            server.stop();
        }
    }

    // -------------------------------------------------------------- 掉线重连

    /**
     * 服务端进程退出 → 客户端发现掉线 → 服务端在原端口重启 → 客户端自动重连并继续同步；
     * 掉线期间本地新增的文件由重连后的第二轮对账补齐。
     */
    private static void testReconnect(File root) throws Exception {
        System.out.println("=== RECONNECT ===");
        File dirS = new File(root, "s");
        File dirC = new File(root, "c");

        Peer server = startServer(dirS, PORT_RECONNECT, SyncMode.BIDIRECTIONAL, false);
        Peer client = connectReconnectClient(dirC, PORT_RECONNECT);
        try {
            check("重连客户端首次连接成功", waitFor(() -> client.sync.isConnected(), 20000));
            waitSessions(server, 1);
            write(new File(dirC, "before.txt"), "before-drop".getBytes("UTF-8"));
            check("掉线前同步正常", null != waitContains(new File(dirS, "before.txt"), 20000, "before-drop"));

            // 服务端下线：监听与端口一起关掉
            server.stop();
            check("客户端发现服务器掉线", waitFor(() -> !client.sync.isConnected(), 20000));

            // 掉线期间本地继续产生改动（此时发不出去，只能等重连后的对账）
            write(new File(dirC, "during-outage.txt"), "during-outage".getBytes("UTF-8"));

            Peer server2 = restartServer(dirS, PORT_RECONNECT);
            try {
                check("客户端自动重连成功（重连次数 +1）", waitFor(() -> 1 <= client.sync.getReconnectCount(), 40000));
                waitSessions(server2, 1);
                write(new File(dirC, "after.txt"), "after-reconnect".getBytes("UTF-8"));
                check("重连后继续推送新文件",
                        null != waitContains(new File(dirS, "after.txt"), 20000, "after-reconnect"));
                check("掉线期间的改动在重连后由对账补齐",
                        null != waitContains(new File(dirS, "during-outage.txt"), 45000, "during-outage"));
                System.out.println("  client state: " + client.sync);
            } finally {
                server2.stop();
            }
        } finally {
            client.stop();
        }
    }

    /**
     * 用带重连能力的客户端连服务器（重试节奏调小，便于自检在几十秒内收敛）
     */
    private static Peer connectReconnectClient(File dir, int port) throws Exception {
        Peer peer = new Peer();
        FileUtils.forceMkdir(dir);
        peer.listener = new FileListener(dir.getAbsolutePath(), BLOCK, TrueFileFilter.INSTANCE);
        peer.handler = new FileSyncEventHandler(peer.listener.getLocalFileAdaptor(), SyncMode.BIDIRECTIONAL);
        peer.sync = new FileSyncClient("localhost", port, "/", peer.handler);
        peer.sync.setInitialRetryMillis(500L);
        peer.sync.setMaxRetryMillis(5000L);
        peer.sync.setHealthCheckMillis(500L);
        peer.sync.start();
        peer.listener.addEventHandler(peer.handler);
        peer.listener.start();
        return peer;
    }

    /**
     * 在原端口重新起服务端：端口可能还在 TIME_WAIT，重试几次
     */
    private static Peer restartServer(File dir, int port) throws Exception {
        Exception last = null;
        for (int i = 0; i < 20; i++) {
            try {
                return startServer(dir, port, SyncMode.BIDIRECTIONAL, false);
            } catch (Exception e) {
                last = e;
                Thread.sleep(500);
            }
        }
        throw last;
    }

    // ---------------------------------------------------------------- 工具

    private static class Peer {
        FileListener listener;
        FileSyncEventHandler handler;
        ArchiveEventHandler archive;
        WebSocketServer server;
        WebSocketClient client;
        FileSyncClient sync;

        RemoteMessageHandler remote() {
            return null == handler ? null : handler.getRemoteMessageHandler(null != server);
        }

        void stop() throws Exception {
            if (null != sync) {
                sync.close();
            }
            if (null != listener) {
                listener.stop();
            }
            if (null != client) {
                client.close();
            }
            if (null != server) {
                server.close();
            }
        }
    }

    private static Peer startServer(File dir, int port, SyncMode mode, boolean multiWay, EventHandler... extras)
            throws Exception {
        Peer peer = new Peer();
        FileUtils.forceMkdir(dir);
        peer.listener = new FileListener(dir.getAbsolutePath(), BLOCK, TrueFileFilter.INSTANCE);
        peer.handler = new FileSyncEventHandler(peer.listener.getLocalFileAdaptor(), mode);
        RemoteMessageHandler remote = peer.handler.getRemoteMessageHandler(true);
        remote.setMultiWaySync(multiWay);
        peer.server = new WebSocketServer(port, 1, remote, 1000);
        peer.server.asyncListen();
        peer.listener.addEventHandler(peer.handler);
        for (EventHandler extra : extras) {
            peer.listener.addEventHandler(extra);
        }
        peer.listener.start();
        return peer;
    }

    /**
     * 服务端 + 归档处理器（归档与同步并行工作）。
     */
    private static Peer startServerWithArchive(File dir, int port, File archiveRoot) throws Exception {
        Peer peer = new Peer();
        FileUtils.forceMkdir(dir);
        peer.listener = new FileListener(dir.getAbsolutePath(), BLOCK, TrueFileFilter.INSTANCE);
        peer.archive = new ArchiveEventHandler(archiveRoot, peer.listener.getLocalFileAdaptor());
        peer.listener.addEventHandler(peer.archive);
        peer.handler = new FileSyncEventHandler(peer.listener.getLocalFileAdaptor(), SyncMode.BIDIRECTIONAL);
        peer.handler.setDeleteRecorder(peer.archive);
        peer.handler.setEchoObserver(peer.archive);
        RemoteMessageHandler remote = peer.handler.getRemoteMessageHandler(true);
        peer.server = new WebSocketServer(port, 1, remote, 1000);
        peer.server.asyncListen();
        peer.listener.addEventHandler(peer.handler);
        peer.archive.initialFullArchive();
        peer.listener.start();
        return peer;
    }

    private static Peer connectClient(File dir, int port, SyncMode mode, EventHandler... extras) throws Exception {        Peer peer = new Peer();
        FileUtils.forceMkdir(dir);
        peer.listener = new FileListener(dir.getAbsolutePath(), BLOCK, TrueFileFilter.INSTANCE);
        peer.handler = new FileSyncEventHandler(peer.listener.getLocalFileAdaptor(), mode);
        peer.client = new WebSocketClient("localhost", port, "/", peer.handler.getRemoteMessageHandler(false));
        peer.client.asyncListen();
        peer.listener.addEventHandler(peer.handler);
        for (EventHandler extra : extras) {
            peer.listener.addEventHandler(extra);
        }
        peer.listener.start();
        return peer;
    }

    /**
     * 起一个带明确节点身份的端点（服务端或客户端），可选挂归档处理器。
     * <p>
     * 三个端点跑在同一个 JVM 里，默认的 {@code NodeInfo.local()} 会是同一个，所以这里显式指定身份，
     * 才能区分出 A / S / C 三个逻辑节点。
     * </p>
     */
    private static Peer startNode(File dir, NodeInfo nodeInfo, boolean server, int port, File archiveRoot)
            throws Exception {
        Peer peer = new Peer();
        FileUtils.forceMkdir(dir);
        peer.listener = new FileListener(dir.getAbsolutePath(), BLOCK, TrueFileFilter.INSTANCE);
        peer.listener.setNodeInfo(nodeInfo);
        peer.handler = new FileSyncEventHandler(peer.listener.getLocalFileAdaptor(), SyncMode.BIDIRECTIONAL);
        peer.handler.setNodeInfo(nodeInfo);
        RemoteMessageHandler remote = peer.handler.getRemoteMessageHandler(server);
        remote.setNodeInfo(nodeInfo);
        // 服务端打开中继：A 的改动才能经它到达 C
        remote.setMultiWaySync(server);
        if (null != archiveRoot) {
            peer.archive = new ArchiveEventHandler(archiveRoot, peer.listener.getLocalFileAdaptor());
            peer.archive.setNodeInfo(nodeInfo);
            peer.handler.setDeleteRecorder(peer.archive);
            // 归档同时旁听回环抑制登记，拿到"这条路径是哪个节点写的"
            peer.handler.setEchoObserver(peer.archive);
            peer.listener.addEventHandler(peer.archive);
            peer.archive.initialFullArchive();
        }
        if (server) {
            peer.server = new WebSocketServer(port, 1, remote, 1000);
            peer.server.asyncListen();
        } else {
            peer.client = new WebSocketClient("localhost", port, "/", remote);
            peer.client.asyncListen();
        }
        peer.listener.addEventHandler(peer.handler);
        peer.listener.start();
        return peer;
    }

    private static void waitSessions(Peer server, int expected) throws Exception {
        if (!waitFor(() -> expected <= server.remote().getSessionCount(), 15000)) {
            throw new IllegalStateException("expected " + expected + " session(s), got " + server.remote().getSessionCount());
        }
    }

    private static boolean waitEqual(File expected, File actual, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (actual.exists() && !actual.isDirectory() && expected.exists() && contentEquals(expected, actual)) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    private static boolean waitFor(BooleanSupplier condition, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(200);
        }
        return condition.getAsBoolean();
    }

    private static boolean contentEquals(File a, File b) throws IOException {
        if (a.length() != b.length()) {
            return false;
        }
        try {
            return Arrays.equals(Files.readAllBytes(a.toPath()), Files.readAllBytes(b.toPath()));
        } catch (IOException e) {
            // 对端还在写、Windows 上文件被占用：当成"还没同步好"，让调用方稍后重试
            return false;
        }
    }

    private static String readText(File file) throws IOException {
        if (!file.isFile()) {
            return null;
        }
        try {
            return new String(Files.readAllBytes(file.toPath()), "UTF-8");
        } catch (IOException e) {
            // 文件被占用时返回 null，调用方用 waitFor 重试
            return null;
        }
    }

    /**
     * 轮询等到文件内容包含全部关键字，返回去掉首尾空白的全文；超时返回 null。
     */
    private static String waitContains(File file, long timeoutMillis, String... needles) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            String text = readText(file);
            if (null != text) {
                boolean all = true;
                for (String needle : needles) {
                    if (0 > text.indexOf(needle)) {
                        all = false;
                        break;
                    }
                }
                if (all) {
                    return text.trim();
                }
            }
            Thread.sleep(200);
        }
        return null;
    }

    /**
     * 等到文件的"长度+修改时间"连续 quietMillis 毫秒没变化，说明对账推送已经停了。
     */
    private static void waitQuiet(File file, long quietMillis, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        long lastStamp = -2;
        long stableSince = 0;
        while (System.currentTimeMillis() < deadline) {
            long stamp = file.exists() ? (file.length() ^ file.lastModified()) : -1;
            if (stamp == lastStamp) {
                if (0 == stableSince) {
                    stableSince = System.currentTimeMillis();
                } else if (quietMillis <= System.currentTimeMillis() - stableSince) {
                    return;
                }
            } else {
                lastStamp = stamp;
                stableSince = 0;
            }
            Thread.sleep(200);
        }
    }

    /**
     * @return 该归档条目的块文件总字节数（用来证明"只存了改动的块"）
     */
    private static long sumBlockBytes(File archiveDir, ArchiveEntry entry) {
        long total = 0;
        File[] files = new File(archiveDir, entry.getPath()).listFiles();
        if (null == files) {
            return 0;
        }
        for (File file : files) {
            if (file.getName().startsWith(entry.getName() + ".b")) {
                total += file.length();
            }
        }
        return total;
    }

    private static void write(File file, byte[] data) throws IOException {
        FileUtils.forceMkdir(file.getParentFile());
        Files.write(file.toPath(), data);
    }

    private static byte[] randomBytes(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name);
        }
    }
}
