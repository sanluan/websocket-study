package org.microprofile.websocket.utils;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

import org.microprofile.common.buffer.MultiByteBuffer;
import org.microprofile.common.utils.EncodeUtils;
import org.microprofile.nio.handler.ChannelContext;
import org.microprofile.websocket.handler.Session;
import org.microprofile.websocket.handler.WebSocketFrame;

public class HttpProtocolUtils {
    private static final String HTTP_ERROR = "HTTP/1.1 500 Internal Server Error\r\nContent-Type: text/html\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n";
    private static final String HTTP_WEBSOCKET_RESPONSE = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept:";
    private static final String ENCODE = "UTF-8";
    private static final String SEC_WEBSOCKET_KEY = "SN3OSin4/Zok8kmgrD8qxQ==";
    private static final String SEC_WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /**
     * 判断 HTTP 头部是否已经接收完整（必须以空行结束）
     *
     * @param multiByteBuffer
     * @return
     * @throws IOException 
     */
    public static boolean isEnough(MultiByteBuffer multiByteBuffer) throws IOException {
        int last = multiByteBuffer.limit() - 1;
        if (0 > last || '\n' != multiByteBuffer.get(last)) {
            return false;
        }
        if (1 <= last && '\n' == multiByteBuffer.get(last - 1)) {
            return true;
        }
        return 3 <= last && '\r' == multiByteBuffer.get(last - 1) && '\n' == multiByteBuffer.get(last - 2)
                && '\r' == multiByteBuffer.get(last - 3);
    }

    public static Session processServerProtocol(ChannelContext<WebSocketFrame> channelContext, MultiByteBuffer multiByteBuffer)
            throws IOException {
        Session session = null;
        String url = readFirstLine(multiByteBuffer);
        Map<String, String> headers = getHeaders(multiByteBuffer);
        String secKey = headers.get("Sec-WebSocket-Key");
        if (null != secKey && null != url) {
            byte[] key_sha1 = sha1(secKey + SEC_WEBSOCKET_GUID);
            StringBuilder sb = new StringBuilder(HTTP_WEBSOCKET_RESPONSE);
            sb.append(EncodeUtils.base64Encode(key_sha1)).append("\r\n\r\n");
            session = new Session(channelContext.getId(), channelContext);
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
        String secAccept = headers.get("Sec-WebSocket-Accept");
        if (null != secAccept && secAccept.equals(expectedAccept())) {
            session = new Session(channelContext.getId(), channelContext);
            session.setClient(true);
            session.setHeaders(headers);
            session.setUrl(null);
        }
        return session;
    }

    /**
     * 客户端握手时应当收到的 Sec-WebSocket-Accept 值
     *
     * @return
     */
    public static String expectedAccept() {
        return EncodeUtils.base64Encode(sha1(SEC_WEBSOCKET_KEY + SEC_WEBSOCKET_GUID));
    }

    /**
     * 解析头部，key 大小写不敏感
     *
     * @param multiByteBuffer
     * @return
     */
    public static Map<String, String> getHeaders(MultiByteBuffer multiByteBuffer) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        String key = null;
        StringBuilder stringBuilder = new StringBuilder();
        while (multiByteBuffer.hasRemaining()) {
            char c = (char) multiByteBuffer.get();
            if (c == '\n' || c == '\r') {
                if (null != key) {
                    headers.put(key, stringBuilder.toString().trim());
                    key = null;
                }
                stringBuilder.setLength(0);
            } else if (null == key && c == ':') {
                key = stringBuilder.toString().trim();
                stringBuilder.setLength(0);
            } else {
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
        sb.append(null == path || path.isEmpty() ? "/" : path).append(" HTTP/1.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nHost: ");
        sb.append(host).append(":").append(port).append("\r\nOrigin: null\r\nSec-WebSocket-Key: ").append(SEC_WEBSOCKET_KEY)
                .append("\r\nSec-WebSocket-Version: 13\r\n\r\n");
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
            mssageDigest.update(text.getBytes(ENCODE));
        } catch (UnsupportedEncodingException e) {
            mssageDigest.update(text.getBytes());
        }
        return mssageDigest.digest();
    }
}
