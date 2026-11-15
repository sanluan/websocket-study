package org.microprofile.test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.microprofile.common.buffer.MultiByteBuffer;
import org.microprofile.websocket.WebSocketClient;
import org.microprofile.websocket.WebSocketServer;
import org.microprofile.websocket.handler.Message;
import org.microprofile.websocket.handler.MessageHandler;
import org.microprofile.websocket.handler.Session;
import org.microprofile.websocket.utils.MessageUtils;

/**
 * 端到端自检：wss 握手、字符串/二进制回显、大报文（126/127 长度分支）、ping/pong 后连接存活、明文 ws 回归。
 */
public class WssSelfTest {
    private static final int PORT_PLAIN = 18111;
    private static final int PORT_SSL = 18222;
    private static final int TIMEOUT = 8000;

    private static final List<String> clientStrings = Collections.synchronizedList(new ArrayList<String>());
    private static final List<byte[]> clientBytes = Collections.synchronizedList(new ArrayList<byte[]>());
    private static final List<String> serverStrings = Collections.synchronizedList(new ArrayList<String>());
    private static final List<byte[]> serverBytes = Collections.synchronizedList(new ArrayList<byte[]>());
    private static final CountDownLatch serverOpen = new CountDownLatch(1);
    private static final CountDownLatch clientOpen = new CountDownLatch(1);
    private static final List<String> failures = Collections.synchronizedList(new ArrayList<String>());

    public static void main(String[] args) throws Exception {
        String keystorePath = "src/test/resources/certs/server.jks";
        String storePass = "changeit";
        KeyStore keyStore = KeyStore.getInstance("JKS");
        System.out.println(new File("").getAbsolutePath());
        try (FileInputStream in = new FileInputStream(keystorePath)) {
            keyStore.load(in, storePass.toCharArray());
        }

        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, storePass.toCharArray());
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keyManagerFactory.getKeyManagers(), null, null);

        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(keyStore);
        SSLContext clientContext = SSLContext.getInstance("TLS");
        clientContext.init(null, trustManagerFactory.getTrustManagers(), null);

        WebSocketServer sslServer = new WebSocketServer("localhost", PORT_SSL, 2, new EchoHandler(serverStrings, serverBytes),
                1000, serverContext, false);
        sslServer.asyncListen();
        WebSocketServer plainServer = new WebSocketServer("localhost", PORT_PLAIN, 2, new EchoHandler(serverStrings, serverBytes),
                1000);
        plainServer.asyncListen();

        WebSocketClient sslClient = new WebSocketClient("wss://localhost:" + PORT_SSL + "/", new ClientHandler(), clientContext,
                false);
        sslClient.asyncListen();
        WebSocketClient plainClient = new WebSocketClient("ws://localhost:" + PORT_PLAIN + "/", new ClientHandler());
        plainClient.asyncListen();

        check("wss 服务端握手完成", serverOpen.await(TIMEOUT, TimeUnit.MILLISECONDS));
        check("wss 客户端握手完成", clientOpen.await(TIMEOUT, TimeUnit.MILLISECONDS));

        // 1. 短字符串（7bit 长度 + 客户端掩码）
        sslClient.sendString("hello-wss");
        await(clientStrings, 1, "wss 客户端收不到服务端回显");
        check("wss 字符串回显", "hello-wss".equals(clientStrings.get(0)));

        // 2. 300 字节（126 长度分支）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append((char) ('a' + i % 26));
        }
        String medium = sb.toString();
        sslClient.sendString(medium);
        await(clientStrings, 2, "wss 客户端收不到 300 字节回显");
        check("wss 300 字节回显", medium.equals(clientStrings.get(1)));

        // 3. 40000 字节（126 长度分支且长于 32767，验证 getShort 符号位扩展）
        byte[] mediumBytes = new byte[40000];
        new Random(7).nextBytes(mediumBytes);
        sslClient.sendByte(mediumBytes);
        await(clientBytes, 1, "wss 客户端收不到 40000 字节回显");
        check("wss 40000 字节二进制回显", Arrays.equals(mediumBytes, clientBytes.get(0)));

        // 4. 200KB 二进制（127 长度分支 + 分块读取 + 分块发送）
        byte[] big = new byte[200 * 1024];
        new Random(42).nextBytes(big);
        sslClient.sendByte(big);
        await(clientBytes, 2, "wss 客户端收不到 200KB 回显");
        check("wss 200KB 二进制回显", Arrays.equals(big, clientBytes.get(1)));

        // 5. ping -> 服务端应回 pong（走 SSL 通道），且连接不能被 pong 关掉
        sslClient.send(new Message(true, 0, Message.OPCODE_PING, new byte[] { 1, 2, 3 }));
        Thread.sleep(500);
        check("收到 pong 后连接保持", sslClient.isOpen());
        sslClient.sendString("after-ping");
        await(clientStrings, 3, "pong 之后 wss 连接不可用");
        check("pong 之后仍可收发", "after-ping".equals(clientStrings.get(2)));

        // 6. 分片消息：首片 FIN=0 + opcode=1，续片 FIN=1 + opcode=0
        sslClient.getChannelContext().write(
                MessageUtils.wrapMessage(new Message(false, 0, Message.OPCODE_STRING, "abc".getBytes("UTF-8")), true, true));
        sslClient.getChannelContext()
                .write(MessageUtils.wrapMessage(new Message(true, 0, Message.OPCODE_PART, "def".getBytes("UTF-8")), true, false));
        await(clientStrings, 4, "分片消息没有正确拼装");
        check("分片消息拼装", "abcdef".equals(clientStrings.get(3)));

        // 7. 明文 ws 回归
        plainClient.sendString("hello-plain");
        await(clientStrings, 5, "明文 ws 收不到回显");
        check("明文 ws 回显", "hello-plain".equals(clientStrings.get(4)));

        // 8. MultiByteBuffer 绝对索引 / 跨缓冲区批量读取
        checkMultiByteBuffer();

        // 9. 客户端通过 Session.send 发送（client=true 分支必须掩码）
        sslClient.sendString("session-send-test");
        long deadline = System.currentTimeMillis() + TIMEOUT;
        while (!clientStrings.contains("session-ok") && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        check("客户端 Session.send 掩码", clientStrings.contains("session-ok"));

        sslClient.close();
        plainClient.close();
        sslServer.close();
        plainServer.close();

        if (0 == failures.size()) {
            System.out.println("RESULT: PASS  (" + clientStrings.size() + " 条回显, " + clientBytes.size() + " 条二进制)");
        } else {
            System.out.println("RESULT: FAIL");
            for (String failure : failures) {
                System.out.println("  - " + failure);
            }
            System.exit(1);
        }
        System.exit(0);
    }

    private static void checkMultiByteBuffer() {
        MultiByteBuffer buffer = new MultiByteBuffer();
        ByteBuffer b1 = ByteBuffer.wrap(new byte[] { 0, 1, 2, 3 });
        ByteBuffer b2 = ByteBuffer.wrap(new byte[] { 10, 11, 12, 13, 14 });
        ByteBuffer b3 = ByteBuffer.wrap(new byte[] { 20, 21 });
        buffer.put(b1).put(b2).put(b3);
        check("MultiByteBuffer 绝对索引（跨缓冲区）",
                13 == buffer.get(7) && 14 == buffer.get(8) && 21 == buffer.get(10) && 0 == buffer.get(0));

        // mark 后跨缓冲区批量读取，再 reset 回退
        buffer.mark();
        byte[] prefix = new byte[6];
        buffer.get(prefix);
        check("MultiByteBuffer 跨缓冲区批量读取", Arrays.equals(new byte[] { 0, 1, 2, 3, 10, 11 }, prefix));
        buffer.reset();
        check("MultiByteBuffer reset 回退", 0 == buffer.get(0) && 3 == buffer.get(3) && 11 == buffer.get(5));

        // 偏移读取：只写 dst 的后半段
        byte[] dst = new byte[8];
        buffer.get(dst, 4, 3);
        check("MultiByteBuffer 带偏移读取", 0 == dst[4] && 1 == dst[5] && 2 == dst[6] && 0 == dst[0] && 3 == buffer.get(3));
    }

    private static void await(List<?> list, int size, String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT;
        while (list.size() < size && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        if (list.size() < size) {
            failures.add(message);
        }
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name);
        if (!ok) {
            failures.add(name);
        }
    }

    static class EchoHandler implements MessageHandler {
        private final List<String> strings;
        private final List<byte[]> bytes;

        EchoHandler(List<String> strings, List<byte[]> bytes) {
            this.strings = strings;
            this.bytes = bytes;
        }

        @Override
        public void onOpen(Session session) throws IOException {
            serverOpen.countDown();
        }

        @Override
        public void onClose(Session session) throws IOException {
        }

        @Override
        public void onMessage(String message, Session session) throws IOException {
            strings.add(message);
            session.sendString(message);
        }

        @Override
        public void onMessage(byte[] message, Session session) throws IOException {
            bytes.add(message);
            session.sendByte(message);
        }
    }

    static class ClientHandler implements MessageHandler {
        @Override
        public void onOpen(Session session) throws IOException {
            clientOpen.countDown();
        }

        @Override
        public void onClose(Session session) throws IOException {
        }

        @Override
        public void onMessage(String message, Session session) throws IOException {
            clientStrings.add(message);
            if ("session-send-test".equals(message)) {
                // 走 Session -> ChannelContext 的客户端发送路径（必须自动掩码）
                session.sendString("session-ok");
            }
        }

        @Override
        public void onMessage(byte[] message, Session session) throws IOException {
            clientBytes.add(message);
        }
    }
}
