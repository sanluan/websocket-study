package org.microprofile.websocket.utils;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

import org.microprofile.common.buffer.MultiByteBuffer;
import org.microprofile.common.utils.EncodeUtils;
import org.microprofile.nio.handler.ChannelContext;
import org.microprofile.websocket.handler.Session;
import org.microprofile.websocket.handler.WebSocketFrame;

public class HttpProtocolUtils {
    private static final String HTTP_ERROR = "HTTP/1.1 500 Internal Server Error\r\nContent-Type: text/html\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n";
    private static final String HTTP_WEBSOCKET_RESPONSE = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept:";
    private static final String ENCODE = "UTF-8";

    public static boolean isEnough(MultiByteBuffer multiByteBuffer) throws IOException {
        if (multiByteBuffer.remaining() > 2 && multiByteBuffer.get(multiByteBuffer.limit() - 1) != (byte) '\n'
                && !(multiByteBuffer.get(multiByteBuffer.limit() - 2) == (byte) '\n'
                        || multiByteBuffer.remaining() > 3 && multiByteBuffer.get(multiByteBuffer.limit() - 2) == (byte) '\r'
                                && multiByteBuffer.get(multiByteBuffer.limit() - 3) == (byte) '\n')) {
            return false;
        } else {
            return true;
        }
    }

    public static Session processServerProtocol(ChannelContext<WebSocketFrame> channelContext, MultiByteBuffer multiByteBuffer)
            throws IOException {
        Session session = null;
        String url = readFirstLine(multiByteBuffer);
        Map<String, String> headers = getHeaders(multiByteBuffer);
        String secKey = headers.get("Sec-WebSocket-Key");
        if (null != secKey && null != url) {
            byte[] key_sha1 = sha1(secKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11");
            StringBuilder sb = new StringBuilder(HTTP_WEBSOCKET_RESPONSE);
            sb.append(EncodeUtils.base64Encode(key_sha1)).append("\r\n\r\n");
            session = new Session(channelContext);
            session.setHeaders(headers);
            session.setUrl(url);
            channelContext.write(sb.toString());
        } else {
            channelContext.write(HTTP_ERROR);
        }
        return session;
    }

    public static Session processClientProtocol(ChannelContext<WebSocketFrame> channelContext, MultiByteBuffer multiByteBuffer)
            throws IOException {
        Session session = null;
        readFirstLine(multiByteBuffer);
        Map<String, String> headers = getHeaders(multiByteBuffer);
        String secKey = headers.get("Sec-WebSocket-Accept");
        if (null != secKey) {
            session = new Session(channelContext);
            session.setHeaders(headers);
            session.setUrl(null);
        }
        return session;
    }

    public static Map<String, String> getHeaders(MultiByteBuffer multiByteBuffer) {
        Map<String, String> headers = new HashMap<>();
        String key = null;
        String value = null;
        StringBuilder stringBuilder = new StringBuilder();
        while (multiByteBuffer.hasRemaining()) {
            char c = (char) multiByteBuffer.get();
            if (c == '\n' || c == '\r') {
                if (null != key) {
                    value = stringBuilder.toString();
                    stringBuilder.setLength(0);
                    headers.put(key, value);
                    key = null;
                }
            } else if (null == key && c == ':') {
                key = stringBuilder.toString();
                stringBuilder.setLength(0);
            } else if (c != ' ') {
                stringBuilder.append(c);
            }
        }
        return headers;
    }

    public static String readFirstLine(MultiByteBuffer multiByteBuffer) {
        StringBuilder stringBuilder = new StringBuilder();
        int index = 0;
        while (multiByteBuffer.hasRemaining()) {
            char c = (char) multiByteBuffer.get();
            if (c == ' ' || c == '\r') {
                if (c == ' ') {
                    index++;
                }
            } else if (c == '\n') {
                break;
            } else if (1 == index) {
                stringBuilder.append(c);
            }
        }
        return stringBuilder.toString();
    }

    public static String getHandshake(String host, int port, String path) {
        StringBuilder sb = new StringBuilder("GET ");
        sb.append(path).append(" HTTP/1.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nHost: ");
        sb.append(host).append(":").append(port).append(
                "\r\nOrigin: null\r\nSec-WebSocket-Key: SN3OSin4/Zok8kmgrD8qxQ==\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Extensions: x-webkit-deflate-frame\r\n\r\n");
        return sb.toString();
    }

    private static byte[] sha1(String text) {
        MessageDigest mssageDigest;
        try {
            mssageDigest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
        try {
            mssageDigest.update(text.getBytes(ENCODE), 0, text.length());
        } catch (UnsupportedEncodingException e) {
            mssageDigest.update(text.getBytes(), 0, text.length());
        }
        return mssageDigest.digest();
    }
}
