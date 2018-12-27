package org.microprofile.websocket.handler;

import java.util.LinkedList;
import java.util.List;

import org.microprofile.common.buffer.MultiByteBuffer;

public class WebSocketFrame {
    private Session session;
    private MultiByteBuffer cachedBuffer;
    private List<byte[]> cachedMessageList;
    private int cachedMessageLength = 0;
    private byte cachedOpCode;
    private boolean initialized;

    /**
     * @return
     */
    public Session getSession() {
        return session;
    }

    /**
     * @param session
     */
    public void setSession(Session session) {
        this.session = session;
    }

    /**
     * @return
     */
    public MultiByteBuffer getCachedBuffer() {
        return cachedBuffer;
    }

    /**
     * @param cachedBuffer
     */
    public void setCachedBuffer(MultiByteBuffer cachedBuffer) {
        this.cachedBuffer = cachedBuffer;
    }

    /**
     * @return the cachedMessageList
     */
    public List<byte[]> getCachedMessageList() {
        return cachedMessageList;
    }

    /**
     * @return the cachedMessageLength
     */
    public int getCachedMessageLength() {
        return cachedMessageLength;
    }

    /**
     * @param messagePayload
     *            the messagePayload to set
     */
    public void addCachedMessage(byte[] messagePayload) {
        if (null == this.cachedMessageList) {
            this.cachedMessageList = new LinkedList<>();
        }
        cachedMessageLength += messagePayload.length;
        this.cachedMessageList.add(messagePayload);
    }

    /**
     */
    public void clearCachedMessageList() {
        if (null == this.cachedMessageList) {
            this.cachedMessageList.clear();
        }
        this.cachedMessageList = null;
        this.cachedMessageLength = 0;
    }

    /**
     * @return the cachedOpCode
     */
    public byte getCachedOpCode() {
        return cachedOpCode;
    }

    /**
     * @param cachedOpCode
     *            the cachedOpCode to set
     */
    public void setCachedOpCode(byte cachedOpCode) {
        this.cachedOpCode = cachedOpCode;
    }

    /**
     * @return
     */
    public boolean isInitialized() {
        return initialized;
    }

    public void setInitialized(boolean initialized) {
        this.initialized = initialized;
    }
}
