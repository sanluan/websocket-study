package org.microprofile.websocket.handler;

import java.nio.ByteBuffer;

public class Message {
    public static final byte OPCODE_PART = 0X0;
    public static final byte OPCODE_STRING = 0X1;
    public static final byte OPCODE_BYTE = 0X2;
    public static final byte OPCODE_CLOSE = 0X8;
    public static final byte OPCODE_PING = 0X9;
    public static final byte OPCODE_PONG = 0XA;
    private final boolean fin;
    private final int rsv;
    private final byte opCode;
    private final ByteBuffer payloadByteBuffer;
    private final int size;

    public Message(boolean fin, int rsv, byte opCode, ByteBuffer payloadByteBuffer) {
        this.fin = fin;
        this.rsv = rsv;
        this.opCode = opCode;
        this.payloadByteBuffer = payloadByteBuffer;
        this.size = payloadByteBuffer.remaining();
    }

    public Message(boolean fin, int rsv, byte opCode, byte[] data) {
        this.fin = fin;
        this.rsv = rsv;
        this.opCode = opCode;
        this.payloadByteBuffer = ByteBuffer.wrap(data);
        this.size = payloadByteBuffer.remaining();
    }

    /**
     * @return
     */
    public boolean isFin() {
        return this.fin;
    }

    /**
     * @return
     */
    public int getRsv() {
        return this.rsv;
    }

    /**
     * @return
     */
    public byte getOpCode() {
        return this.opCode;
    }

    public ByteBuffer put(byte[] data) {
        return payloadByteBuffer.put(data);
    }

    public ByteBuffer put(byte data) {
        return payloadByteBuffer.put(data);
    }

    /**
     * @return
     */
    public byte[] getPayload() {
        return payloadByteBuffer.array();
    }

    /**
     * @return the payloadByteBuffer
     */
    public ByteBuffer getPayloadByteBuffer() {
        return payloadByteBuffer;
    }

    /**
     * @return the size
     */
    public int getSize() {
        return size;
    }
}
