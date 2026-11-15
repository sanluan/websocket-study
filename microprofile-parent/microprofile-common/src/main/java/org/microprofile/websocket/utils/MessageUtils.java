package org.microprofile.websocket.utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.microprofile.common.buffer.MultiByteBuffer;
import org.microprofile.nio.handler.ChannelContext;
import org.microprofile.websocket.handler.Message;
import org.microprofile.websocket.handler.WebSocketFrame;

public class MessageUtils {
    private static final int MAX_CONTROL_PAYLOAD = 125;
    private static final Queue<SecureRandom> randoms = new ConcurrentLinkedQueue<SecureRandom>();

    public static byte[] generateMask() {
        SecureRandom sr = randoms.poll();
        if (sr == null) {
            try {
                sr = SecureRandom.getInstance("SHA1PRNG");
            } catch (NoSuchAlgorithmException e) {
                sr = new SecureRandom();
            }
            randoms.add(sr);
        }
        byte[] result = new byte[4];
        sr.nextBytes(result);
        return result;
    }

    public static boolean isControl(byte opCode) {
        return (0 < (opCode & 0x8));
    }

    public static Message processMessage(MultiByteBuffer byteBuffer, ChannelContext<WebSocketFrame> channelContext)
            throws IOException {
        int r = byteBuffer.remaining();
        if (2 <= r) {
            byte b = byteBuffer.mark().get();
            boolean fin = 0 < (b & 0x80);
            int rsv = ((b & 0x70) >>> 4);
            byte opCode = (byte) (b & 0xF);
            byte b2 = byteBuffer.get();
            boolean hasMask = 0 < (b2 & 0x80);
            int len = (b2 & 0x7F);
            int headLenth = hasMask ? 6 : 2;
            if (126 <= len) {
                if (126 == len) {
                    if (headLenth + 2 > r) {
                        channelContext.setPayloadLength(headLenth + 2);
                        byteBuffer.reset();
                        return null;
                    }
                    headLenth += 2;
                    // 短整型是有符号的，必须去掉符号位扩展
                    len = byteBuffer.getShort() & 0xFFFF;
                } else {
                    if (headLenth + 8 > r) {
                        channelContext.setPayloadLength(headLenth + 8);
                        byteBuffer.reset();
                        return null;
                    }
                    headLenth += 8;
                    byteBuffer.getInt();
                    len = byteBuffer.getInt();
                    if (0 > len) {
                        throw new IOException("message payload is too large");
                    }
                }
            }
            if (isControl(opCode) && (MAX_CONTROL_PAYLOAD < len || !fin)) {
                throw new IOException("invalid websocket control frame");
            }
            if (len + headLenth > r) {
                channelContext.setPayloadLength(headLenth + len);
                byteBuffer.reset();
                return null;
            }
            byte[] array = new byte[len];
            if (hasMask) {
                byte[] mask = new byte[4];
                byteBuffer.get(mask).get(array);
                for (int i = 0; i < array.length; i++) {
                    array[i] = (byte) (array[i] ^ mask[i % 4]);
                }
            } else {
                byteBuffer.get(array);
            }
            channelContext.setPayloadLength(0);
            return new Message(fin, rsv, opCode, array);
        }
        return null;
    }

    public static ByteBuffer wrapMessageHeader(Message message, boolean masked, boolean first, int bufferlength) {
        int length = message.getSize();
        byte b = (byte) (message.isFin() ? -128 : 0);
        b = (byte) (b + (message.getRsv() << 4));
        if (first) {
            b = (byte) (b + message.getOpCode());
        }
        int headLenth = 2;
        byte b1;
        if (masked) {
            b1 = -128;
            headLenth += 4;
        } else {
            b1 = 0;
        }
        if (126 <= length) {
            if (length < 65536) {
                headLenth += 2;
            } else {
                headLenth += 8;
            }
        }
        ByteBuffer byteBuffer = ByteBuffer.allocate(headLenth + bufferlength);
        byteBuffer.put(b);
        if (126 > length) {
            byteBuffer.put((byte) (length | b1));
        } else if (65536 > length) {
            byteBuffer.put((byte) (0x7E | b1));
            byteBuffer.put((byte) (length >>> 8));
            byteBuffer.put((byte) (length & 0xFF));
        } else {
            byteBuffer.put((byte) (0x7F | b1));
            byteBuffer.putInt(0);
            byteBuffer.putInt(length);
        }
        if (masked) {
            byteBuffer.put(generateMask());
        }
        return byteBuffer;
    }

    /**
     * 打包一条完整的 WebSocket 消息，masked 为 true 时按 RFC6455 对负载逐字节掩码
     *
     * @param message
     * @param masked
     *            客户端发送时必须为 true
     * @param first
     * @return
     */
    public static ByteBuffer wrapMessage(Message message, boolean masked, boolean first) {
        ByteBuffer byteBuffer = wrapMessageHeader(message, masked, first, message.getSize());
        ByteBuffer payload = message.getPayloadByteBuffer();
        if (masked) {
            int maskOffset = byteBuffer.position() - 4;
            byte[] mask = new byte[4];
            for (int i = 0; i < mask.length; i++) {
                mask[i] = byteBuffer.get(maskOffset + i);
            }
            for (int i = 0; payload.hasRemaining(); i++) {
                byteBuffer.put((byte) (payload.get() ^ mask[i % 4]));
            }
        } else {
            byteBuffer.put(payload);
        }
        byteBuffer.flip();
        return byteBuffer;
    }
}
