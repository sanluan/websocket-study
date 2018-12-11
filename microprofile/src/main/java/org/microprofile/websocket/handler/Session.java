package org.microprofile.websocket.handler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.UUID;

import org.microprofile.file.constant.Constants;
import org.microprofile.websocket.utils.MessageUtils;

public class Session {
    private String id;
    private SocketChannel socketChannel;

    public Session(SocketChannel socketChannel) {
        this.id = UUID.randomUUID().toString();
        this.socketChannel = socketChannel;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public boolean isOpen() {
        return socketChannel.isOpen();
    }

    public void sendString(String data) throws IOException {
        send(new Message(true, 0, Message.OPCODE_STRING, data.getBytes(Constants.DEFAULT_CHARSET)));
    }

    public void sendByte(byte[] data) throws IOException {
        send(new Message(true, 0, Message.OPCODE_BYTE, data));
    }

    public void send(Message message) throws IOException {
        socketChannel.write(MessageUtils.wrapMessage(message, false, true));
    }

    public void send(ByteBuffer byteBuffer) throws IOException {
        socketChannel.write(byteBuffer);
    }

    public void close() throws IOException {
        socketChannel.close();
    }
}
