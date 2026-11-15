package org.microprofile.test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;
import java.util.function.BooleanSupplier;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.microprofile.file.event.EventHandler;
import org.microprofile.file.event.FileEvent;
import org.microprofile.file.handler.FileEventHandler;
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

    // ---------------------------------------------------------------- 工具

    private static class Peer {
        FileListener listener;
        FileEventHandler handler;
        WebSocketServer server;
        WebSocketClient client;

        RemoteMessageHandler remote() {
            return null == handler ? null : handler.getRemoteMessageHandler(null != server);
        }

        void stop() throws Exception {
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
        peer.handler = new FileEventHandler(peer.listener.getLocalFileAdaptor(), mode);
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

    private static Peer connectClient(File dir, int port, SyncMode mode, EventHandler... extras) throws Exception {
        Peer peer = new Peer();
        FileUtils.forceMkdir(dir);
        peer.listener = new FileListener(dir.getAbsolutePath(), BLOCK, TrueFileFilter.INSTANCE);
        peer.handler = new FileEventHandler(peer.listener.getLocalFileAdaptor(), mode);
        peer.client = new WebSocketClient("localhost", port, "/", peer.handler.getRemoteMessageHandler(false));
        peer.client.asyncListen();
        peer.listener.addEventHandler(peer.handler);
        for (EventHandler extra : extras) {
            peer.listener.addEventHandler(extra);
        }
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
        return Arrays.equals(Files.readAllBytes(a.toPath()), Files.readAllBytes(b.toPath()));
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
