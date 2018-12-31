package org.microprofile.websocket.utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.microprofile.common.buffer.MultiByteBuffer;
import org.microprofile.websocket.handler.Message;
import org.microprofile.websocket.handler.WebSocketFrame;

public class MessageUtils {
    private static final Queue<SecureRandom> randoms = new ConcurrentLinkedQueue<SecureRandom>();

    public static byte[] generateMask() {
        SecureRandom sr = (SecureRandom) randoms.poll();
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

    public static Message processMessage(MultiByteBuffer byteBuffer, WebSocketFrame webSocketFrame) throws IOException {
        if (2 <= byteBuffer.remaining()) {
            byte b = byteBuffer.get();
            boolean fin = 0 < (b & 0x80);
            int rsv = ((b & 0x70) >>> 4);
            byte opCode = (byte) (b & 0xF);
            byte b2 = byteBuffer.get();
            boolean hasMask = 0 < (b2 & 0x80);
            int payloadLen = (b2 & 0x7F);
            byte[] payloadLengthByte = null;
            if (126 <= payloadLen) {
                if (126 == payloadLen) {
                    payloadLengthByte = new byte[2];
                } else {
                    payloadLengthByte = new byte[8];
                }
                byteBuffer.get(payloadLengthByte);
                payloadLen = byteArrayToInt(payloadLengthByte);
            }
            if (hasMask && payloadLen + 4 <= byteBuffer.remaining() || payloadLen <= byteBuffer.remaining()) {
                byte[] array = new byte[payloadLen];
                if (hasMask) {
                    byte[] mask = new byte[4];
                    byteBuffer.get(mask).get(array);
                    for (int i = 0; i < array.length; i++) {
                        array[i] = (byte) (array[i] ^ mask[i % 4]);
                    }
                } else {
                    byteBuffer.get(array);
                }
                if (isControl(opCode)) {
                    if (fin && 125 >= payloadLen) {
                        return new Message(fin, rsv, opCode, array);
                    } else {
                        return new Message(fin, rsv, Message.OPCODE_CLOSE, array);
                    }
                } else {
                    return new Message(fin, rsv, opCode, array);
                }
            } else {
                webSocketFrame.setPayloadLength(payloadLen);
                byteBuffer.position(byteBuffer.position() - 2);
                if (null != payloadLengthByte) {
                    byteBuffer.position(byteBuffer.position() - payloadLengthByte.length);
                }
            }
        }
        return null;
    }

    public static String byte2bits(byte b) {
        int z = b;
        z |= 256;
        String str = Integer.toBinaryString(z);
        int len = str.length();
        return str.substring(len - 8, len);
    }

    protected static int byteArrayToInt(byte[] b) throws IOException {
        int shift = 0;
        int result = 0;
        for (int i = 0 + b.length - 1; i >= 0; --i) {
            result += ((b[i] & 0xFF) << shift);
            shift += 8;
        }
        return result;
    }

    public static ByteBuffer wrapMessage(Message message, boolean masked, boolean first) {
        int length = 0;
        if (null != message.getPayload()) {
            length = message.getPayload().length;
        }
        byte b = 0;
        if (message.isFin()) {
            b = (byte) (b - 128);
        }
        b = (byte) (b + (message.getRsv() << 4));
        if (first) {
            b = (byte) (b + message.getOpCode());
        }
        int headLenth = 1;
        byte b1;
        if (masked) {
            b1 = -128;
            headLenth += 4;
        } else {
            b1 = 0;
        }
        if (length < 126) {
            headLenth += 1;
        } else if (length < 65536) {
            headLenth += 3;
        } else {
            headLenth += 9;
        }
        ByteBuffer byteBuffer = ByteBuffer.allocate(headLenth + length);
        byteBuffer.put(b);
        if (length < 126) {
            byteBuffer.put((byte) (length | b1));
        } else if (length < 65536) {
            byteBuffer.put((byte) (0x7E | b1));
            byteBuffer.put((byte) (length >>> 8));
            byteBuffer.put((byte) (length & 0xFF));
        } else {
            byteBuffer.put((byte) (0x7F | b1));
            byteBuffer.put((byte) 0);
            byteBuffer.put((byte) 0);
            byteBuffer.put((byte) 0);
            byteBuffer.put((byte) 0);
            byteBuffer.put((byte) (length >>> 24));
            byteBuffer.put((byte) (length >>> 16));
            byteBuffer.put((byte) (length >>> 8));
            byteBuffer.put((byte) (length & 0xFF));
        }
        if (masked) {
            byte[] mask = generateMask();
            byteBuffer.put(mask[0]);
            byteBuffer.put(mask[1]);
            byteBuffer.put(mask[2]);
            byteBuffer.put(mask[3]);
        }
        byteBuffer.put(message.getPayload());
        byteBuffer.flip();
        return byteBuffer;
    }
}
