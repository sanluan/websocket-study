package org.microprofile.test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;
import java.util.function.BooleanSupplier;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.microprofile.file.handler.FileEventHandler;
import org.microprofile.file.handler.SyncMode;
import org.microprofile.file.listener.FileListener;
import org.microprofile.websocket.WebSocketClient;
import org.microprofile.websocket.WebSocketServer;

/**
 * filesync 端到端自检：双向、单向推送、单向拉取。
 */
public class FileSyncSelfTest {

    private static final int BLOCK = 64 * 1024;
    private static final int PORT_BIDIR = 20001;
    private static final int PORT_PUSH = 20002;
    private static final int PORT_PULL = 20003;

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
        FileUtils.forceMkdir(dirA);
        FileUtils.forceMkdir(dirB);

        // A 侧准备：小文件、嵌套目录、多块大文件、空文件、与 B 同长度不同内容的分块文件
        write(new File(dirA, "a1.txt"), "hello-from-A".getBytes("UTF-8"));
        write(new File(dirA, "sub/deep.txt"), "nested-in-A".getBytes("UTF-8"));
        byte[] big = randomBytes(300 * 1024, 11);
        write(new File(dirA, "big.bin"), big);
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

        Pair pair = start(dirA, dirB, SyncMode.BIDIRECTIONAL, SyncMode.BIDIRECTIONAL, PORT_BIDIR);
        try {
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
            pair.stop();
        }
    }

    // -------------------------------------------------------------- 单向推送

    private static void testPushOnly(File root) throws Exception {
        System.out.println("=== PUSH_ONLY (客户端只上传) ===");
        File dirA = new File(root, "a");
        File dirB = new File(root, "b");
        FileUtils.forceMkdir(dirA);
        FileUtils.forceMkdir(dirB);
        write(new File(dirA, "srv.txt"), "server-side".getBytes("UTF-8"));
        write(new File(dirB, "cli.txt"), "client-side".getBytes("UTF-8"));

        Pair pair = start(dirA, dirB, SyncMode.BIDIRECTIONAL, SyncMode.PUSH_ONLY, PORT_PUSH);
        try {
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
            pair.stop();
        }
    }

    // -------------------------------------------------------------- 单向拉取

    private static void testPullOnly(File root) throws Exception {
        System.out.println("=== PULL_ONLY (客户端只下载) ===");
        File dirA = new File(root, "a");
        File dirB = new File(root, "b");
        FileUtils.forceMkdir(dirA);
        FileUtils.forceMkdir(dirB);
        write(new File(dirA, "srv.txt"), "server-side".getBytes("UTF-8"));
        write(new File(dirA, "srv-big.bin"), randomBytes(150 * 1024, 21));
        write(new File(dirB, "cli.txt"), "client-side".getBytes("UTF-8"));

        Pair pair = start(dirA, dirB, SyncMode.BIDIRECTIONAL, SyncMode.PULL_ONLY, PORT_PULL);
        try {
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
            pair.stop();
        }
    }

    // ---------------------------------------------------------------- 工具

    private static class Pair {
        FileListener listenerA;
        FileListener listenerB;
        WebSocketServer server;
        WebSocketClient client;
        FileEventHandler handlerA;

        void stop() throws Exception {
            listenerA.stop();
            listenerB.stop();
            client.close();
            server.close();
        }
    }

    private static Pair start(File dirA, File dirB, SyncMode modeA, SyncMode modeB, int port) throws Exception {
        Pair pair = new Pair();
        pair.listenerA = new FileListener(dirA.getAbsolutePath(), BLOCK, TrueFileFilter.INSTANCE);
        pair.handlerA = new FileEventHandler(pair.listenerA.getLocalFileAdaptor(), modeA);
        pair.server = new WebSocketServer(port, 1, pair.handlerA.getRemoteMessageHandler(true), 1000);
        pair.server.asyncListen();
        pair.listenerA.addEventHandler(pair.handlerA);
        pair.listenerA.start();

        Thread.sleep(300);

        pair.listenerB = new FileListener(dirB.getAbsolutePath(), BLOCK, TrueFileFilter.INSTANCE);
        FileEventHandler handlerB = new FileEventHandler(pair.listenerB.getLocalFileAdaptor(), modeB);
        pair.client = new WebSocketClient("localhost", port, "/", handlerB.getRemoteMessageHandler(false));
        pair.client.asyncListen();
        pair.listenerB.addEventHandler(handlerB);
        pair.listenerB.start();

        if (!waitFor(() -> pair.handlerA.getRemoteMessageHandler(true).hasSession(), 10000)) {
            throw new IllegalStateException("server didn't get session");
        }
        return pair;
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
