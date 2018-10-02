package org.microprofile.websocket.handler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.UUID;

import org.microprofile.websocket.utils.MessageUtils;

public class Session {
    private SocketChannel socketChannel;
    private String id;
    private ByteBuffer lastMessage;
    private boolean initialized;

    public Session(SocketChannel socketChannel) {
        this.socketChannel = socketChannel;
        this.id = UUID.randomUUID().toString();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public ByteBuffer getLastMessage() {
        return lastMessage;
    }

    public void setLastMessage(ByteBuffer lastMessage) {
        this.lastMessage = lastMessage;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void setInitialized(boolean initialized) {
        this.initialized = initialized;
    }

    public boolean isOpen() {
        return socketChannel.isOpen();
    }

    public void sendString(String data) throws IOException {
        send(new Message(true, 0, Message.OPCODE_PART_STRING, data.getBytes()));
    }

    public void sendByte(byte[] data) throws IOException {
        send(new Message(true, 0, Message.OPCODE_PART_BYTE, data));
    }

    public void send(Message message) throws IOException {
        socketChannel.write(MessageUtils.wrapMessage(message, false, true));
    }

    public void close() throws IOException {
        socketChannel.close();
    }
}
