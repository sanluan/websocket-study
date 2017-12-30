package org.microprofile.websocket;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.Executors;

import org.microprofile.nio.SocketClient;
import org.microprofile.websocket.handler.WebSocketProtocolHandler;
import org.microprofile.websocket.handler.Message;
import org.microprofile.websocket.handler.MessageHandler;
import org.microprofile.websocket.utils.HttpProtocolUtils;
import org.microprofile.websocket.utils.MessageUtils;

public class WebSocketClient implements Closeable {
    private SocketClient socketClient;

    public WebSocketClient(String host, int prot, MessageHandler messageHandler) throws IOException {
        if (null == messageHandler) {
            throw new IllegalArgumentException("messageHandler can't be null");
        }
        this.socketClient = new SocketClient(host, prot, Executors.newFixedThreadPool(1),
                new WebSocketProtocolHandler(messageHandler, false));
        HttpProtocolUtils.sendHandshake(socketClient.getSocketChannel());
    }

    public void listen() throws IOException {
        socketClient.listen();
    }
    
    public void asyncListen() throws IOException {
        socketClient.asyncListen();
    }

    public void sendString(String data) throws IOException {
        send(new Message(true, 0, Message.OPCODE_PART_STRING, data.getBytes()));
    }

    public void sendByte(byte[] data) throws IOException {
        send(new Message(true, 0, Message.OPCODE_PART_BYTE, data));
    }

    public void send(Message message) throws IOException {
        socketClient.getSocketChannel().write(MessageUtils.wrapMessage(message, false, true));
    }

    public void close() throws IOException {
        socketClient.close();
    }

}
