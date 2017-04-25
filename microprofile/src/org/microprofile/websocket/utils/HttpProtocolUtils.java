package org.microprofile.websocket.utils;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.microprofile.common.utils.Base64Utils;

public class HttpProtocolUtils {
    private static final String HTTP_ERROR = "HTTP/1.1 500 Internal Server Error\r\nContent-Type: text/html\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n";
    private static final String HTTP_WEBSOCKET_RESPONSE = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept:";
    private static final String HTTP_WEBSOCKET_REQUEST = "GET / HTTP/1.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nHost: 127.0.0.1:9020\r\nOrigin: null\r\nSec-WebSocket-Key: SN3OSin4/Zok8kmgrD8qxQ==\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Extensions: x-webkit-deflate-frame";
    private static final String ENCODE = "UTF-8";
    private static MessageDigest mssageDigest;

    public static void processProtocol(SocketChannel client, ByteBuffer byteBuffer) throws IOException {
        byteBuffer.flip();
        StringBuilder stringBuilder = new StringBuilder();
        while (byteBuffer.hasRemaining()) {
            stringBuilder.append((char) byteBuffer.get());
        }
        byteBuffer.clear();
        String request = stringBuilder.toString();
        int keyindex = request.indexOf("Sec-WebSocket-Key:");
        if (0 < keyindex) {
            String key = request.substring(keyindex + 19, keyindex + 43);
            String new_key = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            byte[] key_sha1 = sha1(new_key);
            StringBuilder sb = new StringBuilder(HTTP_WEBSOCKET_RESPONSE);
            sb.append(Base64Utils.encode(key_sha1)).append("\r\n\r\n");
            send(client, sb.toString());
        } else {
            send(client, HTTP_ERROR);
        }
    }

    public static void sendHandshake(SocketChannel client) throws IOException {
        send(client, HTTP_WEBSOCKET_REQUEST);
    }

    private static void send(SocketChannel client, String message) throws IOException {
        send(client, message.getBytes());
    }

    private static void send(SocketChannel client, byte[] message) throws IOException {
        ByteBuffer byteBuffer = ByteBuffer.wrap(message);
        client.write(byteBuffer);
    }

    private static byte[] sha1(String text) {
        if (null == mssageDigest) {
            try {
                mssageDigest = MessageDigest.getInstance("SHA-1");
            } catch (NoSuchAlgorithmException e) {
                return null;
            }
        }
        try {
            mssageDigest.update(text.getBytes(ENCODE), 0, text.length());
        } catch (UnsupportedEncodingException e) {
            mssageDigest.update(text.getBytes(), 0, text.length());
        }
        return mssageDigest.digest();
    }
}
