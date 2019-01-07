package org.microprofile.websocket;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.Executors;

import org.microprofile.nio.SocketClient;
import org.microprofile.nio.handler.ChannelContext;
import org.microprofile.websocket.handler.Message;
import org.microprofile.websocket.handler.MessageHandler;
import org.microprofile.websocket.handler.WebSocketProtocolHandler;
import org.microprofile.websocket.utils.HttpProtocolUtils;
import org.microprofile.websocket.utils.MessageUtils;

public class WebSocketClient implements Closeable {
    private SocketClient socketClient;
    private ChannelContext<?> channelContext;

    public WebSocketClient(String url, MessageHandler messageHandler) throws IOException, URISyntaxException {
        this(new URI(url), messageHandler);
    }

    public WebSocketClient(URI uri, MessageHandler messageHandler) throws IOException {
        this(uri.getHost(), uri.getPort(), uri.getPath(), messageHandler);
    }

    /**
     * @param host
     * @param port
     * @param path
     * @param messageHandler
     * @throws IOException
     */
    public WebSocketClient(String host, int port, String path, MessageHandler messageHandler) throws IOException {
        if (null == messageHandler) {
            throw new IllegalArgumentException("messageHandler can't be null");
        }
        socketClient = new SocketClient(host, port, Executors.newFixedThreadPool(1),
                new WebSocketProtocolHandler(messageHandler, false));
        channelContext = socketClient.getChannelContext();
        channelContext.write(HttpProtocolUtils.getHandshake(host, port, path));
    }

    /**
     * @throws IOException
     */
    public void listen() throws IOException {
        socketClient.listen();
    }

    /**
     * @throws IOException
     */
    public void asyncListen() throws IOException {
        socketClient.asyncListen();
    }

    /**
     * @param data
     * @throws IOException
     */
    public void sendString(String data) throws IOException {
        send(new Message(true, 0, Message.OPCODE_STRING, data.getBytes()));
    }

    /**
     * @param data
     * @throws IOException
     */
    public void sendByte(byte[] data) throws IOException {
        send(new Message(true, 0, Message.OPCODE_BYTE, data));
    }

    /**
     * @param message
     * @throws IOException
     */
    public void send(Message message) throws IOException {
        channelContext.write(MessageUtils.wrapMessage(message, false, true));
    }

    public void close() throws IOException {
        channelContext.close();
    }

    public boolean isOpen() throws IOException {
        return socketClient.isOpen();
    }

    /**
     * @return the channelContext
     */
    public ChannelContext<?> getChannelContext() {
        return channelContext;
    }

}
