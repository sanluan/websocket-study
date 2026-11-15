package org.microprofile.nio.handler;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.UUID;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

import org.microprofile.common.buffer.MultiByteBuffer;

public class ChannelContext<T> implements Closeable {
    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);
    /**
     * 阻塞握手（SocketClient 建连后主动握手）的超时时间
     */
    private static final int HANDSHAKE_TIMEOUT = 30 * 1000;
    /**
     * 写阻塞（对端接收窗口满）的超时时间
     */
    private static final int WRITE_TIMEOUT = 10 * 1000;

    private String id;
    private SocketChannel socketChannel;
    private ProtocolHandler<T> protocolHandler;
    private ThreadHandler<T> threadHandler;
    private SocketProcesser socketProcesser;
    private SSLEngine sslEngine;
    private ByteBuffer netDataIn;
    private ByteBuffer netDataOut;
    private ByteBuffer appDataIn;
    private int blockSize;
    private volatile boolean closed;
    /**
     * 是否已经启动过握手：服务端在收到第一段数据时才启动握手，启动后不能再重复 beginHandshake
     */
    private boolean handshakeStarted;
    private T attachment;

    /**
     * @param protocolHandler
     * @param socketProcesser
     * @param socketChannel
     * @param sslEngine
     * @param blockSize
     */
    public ChannelContext(ProtocolHandler<T> protocolHandler, SocketProcesser socketProcesser, SocketChannel socketChannel,
            SSLEngine sslEngine, int blockSize) {
        this.id = UUID.randomUUID().toString();
        this.socketChannel = socketChannel;
        this.protocolHandler = protocolHandler;
        this.socketProcesser = socketProcesser;
        this.sslEngine = sslEngine;
        this.threadHandler = new ThreadHandler<>(this, socketProcesser);
        this.blockSize = blockSize;
        if (null != sslEngine) {
            createBuffer();
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        threadHandler.close();
        try {
            protocolHandler.close(this);
        } finally {
            // 应用层的 close 回调异常不能导致连接（含 SSL 通道）泄漏
            if (null != sslEngine) {
                try {
                    sslEngine.closeOutbound();
                    if (HandshakeStatus.NEED_WRAP == sslEngine.getHandshakeStatus()) {
                        wrapHandshake();
                    }
                } catch (Exception e) {
                }
            }
            if (socketChannel.isOpen()) {
                socketChannel.close();
            }
        }
    }

    /**
     * 读取应用数据。SSL 通道下同时驱动握手，返回本次解出的应用层字节数（-1 表示对端已关闭）
     *
     * @param byteBuffer
     * @return
     * @throws IOException
     */
    public synchronized int read(ByteBuffer byteBuffer) throws IOException {
        if (null == sslEngine) {
            return socketChannel.read(byteBuffer);
        }
        int count = 0;
        if (!handshakeStarted) {
            // 服务端第一次读到数据时启动握手；客户端在构造时已由 doHandShake 启动
            sslEngine.beginHandshake();
            handshakeStarted = true;
        }
        if (HandshakeStatus.NOT_HANDSHAKING != sslEngine.getHandshakeStatus()) {
            // 非阻塞方式推进握手，缺少网络数据时立即返回，等待下一次可读事件
            count = handshake(byteBuffer, -1);
            if (HandshakeStatus.NOT_HANDSHAKING != sslEngine.getHandshakeStatus()) {
                return count;
            }
        }
        int appCount = readAppData(byteBuffer);
        return 0 > appCount ? count : count + appCount;
    }

    /**
     * 读取 netDataIn 中已经到达的加密数据并解出应用数据
     *
     * @param appBuffer
     * @return 解出的应用数据长度，-1 表示对端关闭
     * @throws IOException
     */
    private int readAppData(ByteBuffer appBuffer) throws IOException {
        int total = 0;
        for (;;) {
            if (!appBuffer.hasRemaining()) {
                return total;
            }
            if (0 < netDataIn.position()) {
                netDataIn.flip();
                SSLEngineResult result = sslEngine.unwrap(netDataIn, appBuffer);
                netDataIn.compact();
                doTask();
                total += result.bytesProduced();
                Status status = result.getStatus();
                if (Status.OK == status) {
                    continue;
                } else if (Status.BUFFER_OVERFLOW == status) {
                    // 应用缓冲区不足，先把已解出的数据交给上层，剩余记录下次继续
                    return 0 < total ? total : 0;
                } else if (Status.CLOSED == status) {
                    try {
                        sslEngine.closeInbound();
                    } catch (SSLException e) {
                    }
                    return 0 < total ? total : -1;
                }
                // BUFFER_UNDERFLOW：记录不完整，继续从 socket 读
            }
            int count = socketChannel.read(netDataIn);
            if (0 > count) {
                try {
                    sslEngine.closeInbound();
                } catch (SSLException e) {
                }
                return 0 < total ? total : -1;
            }
            if (0 == count) {
                return total;
            }
        }
    }

    private void createBuffer() {
        SSLSession session = sslEngine.getSession();
        int packetBufferSize = session.getPacketBufferSize();
        blockSize = blockSize < session.getApplicationBufferSize() ? session.getApplicationBufferSize() : blockSize;
        netDataOut = ByteBuffer.allocate(packetBufferSize);
        netDataIn = ByteBuffer.allocate(packetBufferSize);
        appDataIn = ByteBuffer.allocate(blockSize);
    }

    /**
     * 阻塞式握手，供 SocketClient 建连后、发送应用数据前调用
     *
     * @throws IOException
     */
    public synchronized void doHandShake() throws IOException {
        if (null == sslEngine) {
            return;
        }
        if (!handshakeStarted) {
            sslEngine.beginHandshake();
            handshakeStarted = true;
        }
        handshake(appDataIn, System.currentTimeMillis() + HANDSHAKE_TIMEOUT);
        appDataIn.clear();
        if (HandshakeStatus.NOT_HANDSHAKING != sslEngine.getHandshakeStatus()) {
            throw new IOException("ssl handshake timeout");
        }
    }

    /**
     * 驱动握手。deadline 小于 0 表示非阻塞：没有待处理数据时立即返回
     *
     * @param appBuffer
     * @param deadline
     * @return 握手期间解出的应用数据长度
     * @throws IOException
     */
    private int handshake(ByteBuffer appBuffer, long deadline) throws IOException {
        int total = 0;
        while (HandshakeStatus.NOT_HANDSHAKING != sslEngine.getHandshakeStatus()) {
            switch (sslEngine.getHandshakeStatus()) {
            case NEED_TASK:
                doTask();
                break;
            case NEED_WRAP:
                wrapHandshake();
                break;
            case NEED_UNWRAP:
                if (0 == netDataIn.position()) {
                    if (0 == appBuffer.remaining() && 0 < total) {
                        return total;
                    }
                    int count = socketChannel.read(netDataIn);
                    if (0 > count) {
                        throw new IOException("ssl handshake failed: connection closed by peer");
                    }
                    if (0 == count) {
                        if (0 < total) {
                            return total;
                        }
                        if (0 > deadline) {
                            return 0;
                        }
                        if (System.currentTimeMillis() > deadline) {
                            throw new IOException("ssl handshake timeout");
                        }
                        sleepQuietly();
                        continue;
                    }
                }
                netDataIn.flip();
                SSLEngineResult result = sslEngine.unwrap(netDataIn, appBuffer);
                netDataIn.compact();
                total += result.bytesProduced();
                doTask();
                if (Status.CLOSED == result.getStatus()) {
                    throw new IOException("ssl engine closed during handshake");
                }
                break;
            default:
                // NEED_UNWRAP_AGAIN 等状态交给下一轮循环重新判断
                break;
            }
        }
        return total;
    }

    /**
     * 把握手中需要发送的数据（ClientHello/ServerHello/Finished 等）写入 socket
     *
     * @throws IOException
     */
    private void wrapHandshake() throws IOException {
        netDataOut.clear();
        SSLEngineResult result = sslEngine.wrap(EMPTY_BUFFER, netDataOut);
        doTask();
        netDataOut.flip();
        if (netDataOut.hasRemaining()) {
            writeFully(netDataOut);
        }
        if (Status.CLOSED == result.getStatus()) {
            throw new IOException("ssl engine closed during handshake");
        }
    }

    public ByteBuffer wrap(ByteBuffer src) throws SSLException {
        netDataOut.clear();
        sslEngine.wrap(src, netDataOut);
        doTask();
        netDataOut.flip();
        return netDataOut;
    }

    public void doTask() {
        Runnable task;
        while ((task = sslEngine.getDelegatedTask()) != null) {
            socketProcesser.execute(task);
        }
    }

    /**
     * @param src
     * @return
     * @throws IOException
     */
    public synchronized int write(MultiByteBuffer src) throws IOException {
        ByteBuffer temp = ByteBuffer.allocate(src.remaining());
        src.get(temp.array());
        return write(temp);
    }

    /**
     * @param src
     * @return
     * @throws IOException
     */
    public synchronized int write(ByteBuffer src) throws IOException {
        if (null == sslEngine) {
            return writeFully(src);
        }
        int written = 0;
        while (src.hasRemaining()) {
            if (!handshakeStarted) {
                sslEngine.beginHandshake();
                handshakeStarted = true;
            }
            if (HandshakeStatus.NOT_HANDSHAKING != sslEngine.getHandshakeStatus()) {
                // 握手未完成时不能阻塞在写路径上，否则会长时间占用读线程需要持有的锁
                handshake(appDataIn, -1);
                appDataIn.clear();
                if (HandshakeStatus.NOT_HANDSHAKING != sslEngine.getHandshakeStatus()) {
                    throw new IOException("ssl handshake not finished");
                }
            }
            netDataOut.clear();
            SSLEngineResult result = sslEngine.wrap(src, netDataOut);
            doTask();
            netDataOut.flip();
            if (netDataOut.hasRemaining()) {
                writeFully(netDataOut);
            }
            if (Status.CLOSED == result.getStatus()) {
                throw new IOException("ssl engine closed");
            }
            if (Status.BUFFER_OVERFLOW == result.getStatus()) {
                netDataOut = ByteBuffer.allocate(netDataOut.capacity() << 1);
                continue;
            }
            written += result.bytesConsumed();
            if (0 == result.bytesConsumed() && 0 == result.bytesProduced()) {
                throw new IOException("ssl engine made no progress");
            }
        }
        return written;
    }

    /**
     * 写到 socket 直到写完，非阻塞情况下等待可写
     *
     * @param buffer
     * @return 写入的字节数
     * @throws IOException
     */
    private int writeFully(ByteBuffer buffer) throws IOException {
        int total = 0;
        long deadline = System.currentTimeMillis() + WRITE_TIMEOUT;
        while (buffer.hasRemaining()) {
            int count = socketChannel.write(buffer);
            if (0 < count) {
                total += count;
                continue;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IOException("write timeout");
            }
            sleepQuietly();
        }
        return total;
    }

    private void sleepQuietly() throws IOException {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for socket", e);
        }
    }

    /**
     * @param message
     * @return
     * @throws IOException
     */
    public int write(String message) throws IOException {
        return write(message.getBytes());
    }

    /**
     * @param message
     * @return
     * @throws IOException
     */
    public int write(byte[] message) throws IOException {
        ByteBuffer byteBuffer = ByteBuffer.wrap(message);
        return write(byteBuffer);
    }

    /**
     * @return the client
     */
    public SocketChannel getSocketChannel() {
        return socketChannel;
    }

    /**
     * @return the protocolHandler
     */
    public ProtocolHandler<T> getProtocolHandler() {
        return protocolHandler;
    }

    /**
     * @return the threadHandler
     */
    public ThreadHandler<T> getThreadHandler() {
        return threadHandler;
    }

    /**
     * @return the closed
     */
    public boolean isOpen() {
        return !closed && socketChannel.isOpen() && socketChannel.isConnected();
    }

    /**
     * @return the attachment
     */
    public T getAttachment() {
        return attachment;
    }

    /**
     * @return the id
     */
    public String getId() {
        return id;
    }

    /**
     * @param attachment
     *            the attachment to set
     */
    public void setAttachment(T attachment) {
        this.attachment = attachment;
    }

    /**
     * @param payloadLength
     */
    public void setPayloadLength(int payloadLength) {
        this.threadHandler.setPayloadLength(payloadLength);
    }

    /**
     * @return the blockSize
     */
    public int getBlockSize() {
        return blockSize;
    }

    /**
     * @return the sslEngine
     */
    public SSLEngine getSslEngine() {
        return sslEngine;
    }
}
