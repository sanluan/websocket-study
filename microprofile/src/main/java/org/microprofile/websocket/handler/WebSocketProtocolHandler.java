package org.microprofile.websocket.handler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.microprofile.common.buffer.MultiByteBuffer;
import org.microprofile.file.constant.Constants;
import org.microprofile.nio.handler.ChannelContext;
import org.microprofile.nio.handler.ProtocolHandler;
import org.microprofile.websocket.utils.HttpProtocolUtils;
import org.microprofile.websocket.utils.MessageUtils;

/**
 * @author zhangxdr
 *
 */
public class WebSocketProtocolHandler implements ProtocolHandler<WebSocketFrame> {
    protected final Log log = LogFactory.getLog(getClass());
    private MessageHandler handler;
    private boolean server = true;

    public WebSocketProtocolHandler(MessageHandler handler) {
        this.handler = handler;
    }

    public WebSocketProtocolHandler(MessageHandler handler, boolean server) {
        this.handler = handler;
        this.server = server;
    }

    public <T> T getData(T t) {
        return t;
    }

    @Override
    public void read(ChannelContext<WebSocketFrame> channelContext, ByteBuffer byteBuffer) throws IOException {
        WebSocketFrame frame = channelContext.getAttachment();
        if (null == frame) {
            frame = new WebSocketFrame();
            channelContext.setAttachment(frame);
        }
        try {
            SocketChannel socketChannel = channelContext.getSocketChannel();
            if (frame.isInitialized()) {
                MultiByteBuffer multiByteBuffer = frame.getCachedBuffer();
                if (null != multiByteBuffer) {
                    frame.setCachedBuffer(null);
                } else {
                    multiByteBuffer = new MultiByteBuffer();
                }
                byteBuffer.flip();
                multiByteBuffer.put(byteBuffer);
                Message message = MessageUtils.processMessage(multiByteBuffer);
                boolean flag = false;
                while (null != message) {
                    flag = true;
                    if (MessageUtils.isControl(message.getOpCode())) {
                        if (Message.OPCODE_CLOSE == message.getOpCode()) {
                            channelContext.close();
                            break;
                        } else if (Message.OPCODE_PING == message.getOpCode()) {
                            Message pongMessage = new Message(message.isFin(), message.getRsv(), Message.OPCODE_PONG,
                                    message.getPayload());
                            socketChannel.write(MessageUtils.wrapMessage(pongMessage, false, true));
                        } else {
                            channelContext.close();
                            break;
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
                            break;
                        }
                    } else if (!message.isFin()) {
                        if (0 != frame.getCachedMessageLength()) {
                            frame.setCachedOpCode(message.getOpCode());
                        }
                        frame.addCachedMessage(message.getPayload());
                    } else {
                        channelContext.close();
                        break;
                    }
                    message = MessageUtils.processMessage(multiByteBuffer);
                }
                if (multiByteBuffer.hasRemaining()) {
                    if (log.isDebugEnabled()) {
                        log.debug("byteBuffer还有剩余：" + multiByteBuffer.remaining());
                    }
                    if (flag) {
                        multiByteBuffer = new MultiByteBuffer();
                        multiByteBuffer.put(byteBuffer);
                        System.out.println(multiByteBuffer);
                    }
                    frame.setCachedBuffer(multiByteBuffer);
                }
            } else {
                if (server) {
                    HttpProtocolUtils.processProtocol(socketChannel, byteBuffer);
                }
                frame.setSession(new Session(socketChannel));
                frame.setInitialized(true);
                handler.onOpen(frame.getSession());
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
