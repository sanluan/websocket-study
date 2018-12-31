package org.microprofile.websocket.handler;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.microprofile.common.buffer.MultiByteBuffer;
import org.microprofile.file.constant.Constants;
import org.microprofile.nio.handler.ChannelContext;
import org.microprofile.nio.handler.ProtocolHandler;
import org.microprofile.websocket.utils.HttpProtocolUtils;
import org.microprofile.websocket.utils.MessageUtils;

/**
 *
 */
public class WebSocketProtocolHandler implements ProtocolHandler<WebSocketFrame> {
    protected final Log log = LogFactory.getLog(getClass());
    private MessageHandler handler;
    private boolean server = true;
    private int maxHeaderLength = 1024 * 10;

    public WebSocketProtocolHandler(MessageHandler handler) {
        this.handler = handler;
    }

    public WebSocketProtocolHandler(MessageHandler handler, boolean server) {
        this.handler = handler;
        this.server = server;
    }

    @Override
    public void read(ChannelContext<WebSocketFrame> channelContext, ByteBuffer byteBuffer) throws IOException {
        WebSocketFrame frame = channelContext.getAttachment();
        if (null == frame) {
            channelContext.setAttachment(frame = new WebSocketFrame());
        }
        byteBuffer.flip();
        try {
            MultiByteBuffer multiByteBuffer = frame.getCachedBuffer();
            if (null == multiByteBuffer) {
                multiByteBuffer = new MultiByteBuffer();
            }
            multiByteBuffer.put(byteBuffer);
            if (frame.isInitialized()) {
                if (frame.getPayloadLength() > multiByteBuffer.remaining()) {
                    return;
                }
                Message message = MessageUtils.processMessage(multiByteBuffer, frame);
                while (null != message) {
                    if (MessageUtils.isControl(message.getOpCode())) {
                        if (Message.OPCODE_CLOSE == message.getOpCode()) {
                            channelContext.close();
                            return;
                        } else if (Message.OPCODE_PING == message.getOpCode()) {
                            Message pongMessage = new Message(message.isFin(), message.getRsv(), Message.OPCODE_PONG,
                                    message.getPayload());
                            channelContext.getSocketChannel().write(MessageUtils.wrapMessage(pongMessage, false, true));
                        } else {
                            channelContext.close();
                            return;
                        }
                    } else if (message.isFin()) {
                        if (0 != frame.getCachedMessageLength() && Message.OPCODE_PART == message.getOpCode()) {
                            ByteBuffer newByteBuffer = ByteBuffer.allocate(frame.getCachedMessageLength());
                            for (byte[] payload : frame.getCachedMessageList()) {
                                newByteBuffer.put(payload);
                            }
                            frame.clearCachedMessageList();
                            newByteBuffer.put(message.getPayload());
                            message = new Message(message.isFin(), frame.getCachedOpCode(), message.getOpCode(),
                                    newByteBuffer.array());
                        }
                        if (Message.OPCODE_BYTE == message.getOpCode()) {
                            handler.onMessage(message.getPayload(), frame.getSession());
                        } else if (Message.OPCODE_STRING == message.getOpCode()) {
                            handler.onMessage(new String(message.getPayload(), Constants.DEFAULT_CHARSET), frame.getSession());
                        } else {
                            channelContext.close();
                            return;
                        }
                    } else if (!message.isFin()) {
                        if (0 != frame.getCachedMessageLength()) {
                            frame.setCachedOpCode(message.getOpCode());
                        }
                        frame.addCachedMessage(message.getPayload());
                    } else {
                        channelContext.close();
                        return;
                    }
                    message = MessageUtils.processMessage(multiByteBuffer, frame);
                }
                if (byteBuffer.hasRemaining()) {
                    if (multiByteBuffer.size() > 1) {
                        multiByteBuffer = new MultiByteBuffer();
                        multiByteBuffer.put(byteBuffer);
                    }
                    frame.setCachedBuffer(multiByteBuffer);
                } else {
                    frame.setCachedBuffer(null);
                    frame.setPayloadLength(0);
                }
            } else {
                if (!HttpProtocolUtils.isEnough(multiByteBuffer)) {
                    frame.setCachedBuffer(multiByteBuffer);
                    if (multiByteBuffer.remaining() > maxHeaderLength) {
                        channelContext.close();
                    }
                    return;
                }
                Session session;
                if (server) {
                    session = HttpProtocolUtils.processServerProtocol(channelContext.getSocketChannel(), multiByteBuffer);
                } else {
                    session = HttpProtocolUtils.processClientProtocol(channelContext.getSocketChannel(), multiByteBuffer);
                }
                if (null == session) {
                    channelContext.close();
                    return;
                } else {
                    frame.setSession(session);
                    frame.setInitialized(true);
                    handler.onOpen(frame.getSession());
                }
                frame.setCachedBuffer(null);
            }
        } catch (IOException e) {
            channelContext.close();
        }
    }

    @Override
    public void close(ChannelContext<WebSocketFrame> channelContext) throws IOException {
        WebSocketFrame frame = channelContext.getAttachment();
        if (null != frame && frame.isInitialized()) {
            handler.onClose(frame.getSession());
        }
    }
}
