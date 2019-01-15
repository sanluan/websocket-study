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

    public static Message processMessage(MultiByteBuffer byteBuffer, ChannelContext<WebSocketFrame> channelContext)
            throws IOException {
        if (2 <= byteBuffer.remaining()) {
            byte b = byteBuffer.mark().get();
            boolean fin = 0 < (b & 0x80);
            int rsv = ((b & 0x70) >>> 4);
            byte opCode = (byte) (b & 0xF);
            byte b2 = byteBuffer.get();
            boolean hasMask = 0 < (b2 & 0x80);
            int payloadLen = (b2 & 0x7F);
            int payloadLength;
            if (126 <= payloadLen) {
                if (126 == payloadLen) {
                    if (2 <= byteBuffer.remaining()) {
                        payloadLength = byteBuffer.getShort();
                    } else {
                        channelContext.setPayloadLength(4);
                        byteBuffer.reset();
                        return null;
                    }
                } else {
                    if (8 <= byteBuffer.remaining()) {
                        byteBuffer.getInt();
                        payloadLength = byteBuffer.getInt();
                    } else {
                        channelContext.setPayloadLength(10);
                        byteBuffer.reset();
                        return null;
                    }
                }
            } else {
                payloadLength = payloadLen;
            }
            if (hasMask && payloadLength + 4 <= byteBuffer.remaining() || payloadLength <= byteBuffer.remaining()) {
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
                channelContext.setPayloadLength(0);
                if (isControl(opCode)) {
                    if (fin && 125 >= payloadLength) {
                        return new Message(fin, rsv, opCode, array);
                    } else {
                        return new Message(fin, rsv, Message.OPCODE_CLOSE, array);
                    }
                } else {
                    return new Message(fin, rsv, opCode, array);
                }
            } else {
                if (126 <= payloadLen) {
                    channelContext.setPayloadLength(payloadLen + (hasMask ? 6 : 2));
                } else {
                    channelContext.setPayloadLength(payloadLen + 126 == payloadLen ? 2 : 8 + (hasMask ? 6 : 2));
                }
                byteBuffer.reset();
            }
        }
        return null;
    }

    public static ByteBuffer wrapMessage(Message message, boolean masked, boolean first) {
        int length = message.getSize();
        byte b = (byte) (message.isFin() ? -128 : 0);
        b += (message.getRsv() << 4);
        if (first) {
            b += message.getOpCode();
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
            byteBuffer.putInt(0);
            byteBuffer.putInt(length);
        }
        if (masked) {
            byteBuffer.put(generateMask());
        }
        byteBuffer.put(message.getPayloadByteBuffer()).flip();
        return byteBuffer;
    }
}
