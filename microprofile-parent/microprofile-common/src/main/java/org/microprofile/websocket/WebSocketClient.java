package org.microprofile.websocket;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;

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

    public WebSocketClient(String url, MessageHandler messageHandler)
            throws IOException, URISyntaxException, NoSuchAlgorithmException, KeyManagementException {
        this(new URI(url), messageHandler, null, false);
    }

    public WebSocketClient(String url, MessageHandler messageHandler, SSLContext sslContext, boolean needClientAuth)
            throws IOException, URISyntaxException, NoSuchAlgorithmException, KeyManagementException {
        this(new URI(url), messageHandler, sslContext, needClientAuth);
    }

    public WebSocketClient(URI uri, MessageHandler messageHandler, SSLContext sslContext, boolean needClientAuth)
            throws IOException, NoSuchAlgorithmException, KeyManagementException {
        this(uri.getHost(), -1 == uri.getPort() ? "ws".equalsIgnoreCase(uri.getScheme()) ? 80 : 443 : uri.getPort(),
                "wss".equalsIgnoreCase(uri.getScheme()), uri.getPath(), messageHandler, sslContext, needClientAuth);
    }

    public WebSocketClient(String host, int port, String path, MessageHandler messageHandler)
            throws IOException, NoSuchAlgorithmException, KeyManagementException {
        this(host, port, false, path, messageHandler, null, false);
    }

    /**
     * @param host
     * @param port
     * @param ssl
     * @param path
     * @param messageHandler
     * @param sslContext
     * @param needClientAuth
     * @throws IOException
     * @throws NoSuchAlgorithmException
     * @throws KeyManagementException
     */
    public WebSocketClient(String host, int port, boolean ssl, String path, MessageHandler messageHandler, SSLContext sslContext,
            boolean needClientAuth) throws IOException, NoSuchAlgorithmException, KeyManagementException {
        if (null == messageHandler) {
            throw new IllegalArgumentException("messageHandler can't be null");
        }
        if (ssl && null == sslContext) {
            sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, null, null);
        }
        socketClient = new SocketClient(host, port, Executors.newFixedThreadPool(1),
                new WebSocketProtocolHandler(messageHandler, false), sslContext, needClientAuth);
        channelContext = socketClient.getChannelContext();
        if (ssl) {
            channelContext.doHandShake();
        }
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
