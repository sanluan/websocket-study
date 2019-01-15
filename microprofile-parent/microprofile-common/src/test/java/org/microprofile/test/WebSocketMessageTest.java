package org.microprofile.test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.microprofile.common.buffer.MultiByteBuffer;
import org.microprofile.nio.handler.ChannelContext;
import org.microprofile.websocket.handler.Message;
import org.microprofile.websocket.handler.WebSocketFrame;
import org.microprofile.websocket.utils.MessageUtils;

public class WebSocketMessageTest {
    public static void main(String[] args) throws IOException {
        byte[] randBytes = new byte[127];
        for (int j = 0; j < 127; j++) {
            randBytes[j] = (byte) (j % 126);
        }
        MultiByteBuffer multiByteBuffer = new MultiByteBuffer();
        for (int i = 0; i <= 1000; i++) {
            ByteBuffer byteBuffer = MessageUtils.wrapMessage(new Message(true, 0, Message.OPCODE_BYTE, randBytes), false, true);
            multiByteBuffer.put(byteBuffer);
        }
        ChannelContext<WebSocketFrame> channelContext = new ChannelContext<>(null, null, null);
        Message message = MessageUtils.processMessage(multiByteBuffer, channelContext);
        while (null != message) {
            if (MessageUtils.isControl(message.getOpCode())) {
                System.out.println("error");
            } else if (message.isFin()) {
                if (Message.OPCODE_BYTE == message.getOpCode()) {
                    if (!Arrays.equals(randBytes, message.getPayload())) {
                        System.out.println("error");
                    }
                } else {
                    System.out.println("error");
                }
            } else if (!message.isFin()) {
                System.out.println("error");
            } else {
                System.out.println("error");
            }
            message = MessageUtils.processMessage(multiByteBuffer, channelContext);
        }
    }
}
