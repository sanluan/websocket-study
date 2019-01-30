package org.microprofile.nio.handler;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.UUID;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

public class ChannelContext<T> implements Closeable {
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
    private boolean closed;
    private T attachment;
    private static final ByteBuffer outData = ByteBuffer.wrap("Hello".getBytes());

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
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            threadHandler.close();
            protocolHandler.close(this);
            if (null != sslEngine) {
                sslEngine.closeOutbound();
            }
        }
        if (socketChannel.isOpen()) {
            socketChannel.close();
        }
    }

    public int read(ByteBuffer byteBuffer) throws IOException {
        if (null == sslEngine) {
            return socketChannel.read(byteBuffer);
        } else {
            if (HandshakeStatus.NOT_HANDSHAKING != sslEngine.getHandshakeStatus()) {
                doHandShake();
            }
            int count = socketChannel.read(netDataIn);
            if (0 < count) {
                netDataIn.flip();
                SSLEngineResult engineResult = sslEngine.unwrap(netDataIn, byteBuffer);
                doTask();
                if (engineResult.getStatus() == SSLEngineResult.Status.OK) {
                    if (netDataIn.remaining() > 0) {
                        netDataIn.compact();
                    } else {
                        netDataIn.clear();
                    }
                } else {
                    count = 0;
                }
            }
            return count;
        }
    }

    private void createBuffer() {
        SSLSession session = sslEngine.getSession();
        int packetBufferSize = session.getPacketBufferSize();
        blockSize = blockSize < session.getApplicationBufferSize() ? session.getApplicationBufferSize() : blockSize;
        netDataOut = ByteBuffer.allocate(packetBufferSize);
        netDataIn = ByteBuffer.allocate(packetBufferSize);
        appDataIn = ByteBuffer.allocate(blockSize);
        netDataOut.clear();
        netDataIn.clear();
        appDataIn.clear();
    }

    public void doHandShake() throws IOException {
        sslEngine.beginHandshake();
        while (HandshakeStatus.NOT_HANDSHAKING != sslEngine.getHandshakeStatus()) {
            switch (sslEngine.getHandshakeStatus()) {
            case NEED_TASK:
                doTask();
                break;
            case NEED_UNWRAP:
                int count = socketChannel.read(netDataIn);
                if (0 <= count) {
                    netDataIn.flip();
                    SSLEngineResult result;
                    do {
                        appDataIn.clear();
                        result = sslEngine.unwrap(netDataIn, appDataIn);
                        doTask();
                    } while (result.getStatus() == SSLEngineResult.Status.OK
                            && sslEngine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_UNWRAP);
                    if (netDataIn.remaining() > 0) {
                        netDataIn.compact();
                    } else {
                        netDataIn.clear();
                    }
                } else {
                    socketChannel.close();
                }
                break;
            case NEED_WRAP:
                outData.flip();
                wrap(outData);
                socketChannel.write(netDataOut);
                break;
            default:
                break;
            }
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
    public int write(ByteBuffer src) throws IOException {
        int i = 0, j = 0;
        if (null != sslEngine) {
            src = wrap(src);
        }
        while (src.hasRemaining()) {
            i = socketChannel.write(src);
            if (i == 0) {
                if (++j > 10) {
                    throw new IOException();
                } else {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                    }
                }
            }
        }
        return i;
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
        return socketChannel.isOpen() && socketChannel.isConnected();
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
}
