package org.microprofile.websocket.handler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.microprofile.file.constant.Constants;
import org.microprofile.nio.handler.ProtocolHandler;
import org.microprofile.websocket.utils.HttpProtocolUtils;
import org.microprofile.websocket.utils.MessageUtils;

/**
 * @author zhangxdr
 *
 */
public class WebSocketProtocolHandler implements ProtocolHandler {
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

    @Override
    public void read(SelectionKey key, ByteBuffer byteBuffer) throws IOException {
        SocketChannel socketChannel = (SocketChannel) key.channel();
        WebSocketFrame frame = (WebSocketFrame) key.attachment();
        if (null == frame) {
            frame = new WebSocketFrame();
            key.attach(frame);
        }
        try {
            if (frame.isInitialized()) {
                ByteBuffer lastMessage = frame.getCachedBuffer();
                if (null != lastMessage) {
                    frame.setCachedBuffer(null);
                    byteBuffer.flip();
                    ByteBuffer newByteBuffer = ByteBuffer.allocate(lastMessage.remaining() + byteBuffer.remaining());
                    newByteBuffer.put(lastMessage);
                    byteBuffer = newByteBuffer.put(byteBuffer);
                }
                byteBuffer.flip();
                Message message = MessageUtils.processMessage(byteBuffer);
                while (null != message) {
                    if (MessageUtils.isControl(message.getOpCode())) {
                        if (Message.OPCODE_CLOSE == message.getOpCode()) {
                            close(key);
                            break;
                        } else if (Message.OPCODE_PING == message.getOpCode()) {
                            Message pongMessage = new Message(message.isFin(), message.getRsv(), Message.OPCODE_PONG,
                                    message.getPayload());
                            socketChannel.write(MessageUtils.wrapMessage(pongMessage, false, true));
                        } else {
                            close(key);
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
                            close(key);
                            break;
                        }
                    } else if (!message.isFin()) {
                        if (0 != frame.getCachedMessageLength()) {
                            frame.setCachedOpCode(message.getOpCode());
                        }
                        frame.addCachedMessage(message.getPayload());
                    } else {
                        close(key);
                        break;
                    }
                    message = MessageUtils.processMessage(byteBuffer);
                }
                if (byteBuffer.hasRemaining()) {
                    frame.setCachedBuffer(byteBuffer);
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
            e.printStackTrace();
            if (null != frame && frame.isInitialized()) {
                handler.onClose(frame.getSession());
            }
        }
    }

    @Override
    public void close(SelectionKey key) throws IOException {
        WebSocketFrame frame = (WebSocketFrame) key.attachment();
        if (null != frame && frame.isInitialized()) {
            handler.onClose(frame.getSession());
        }
    }
}
