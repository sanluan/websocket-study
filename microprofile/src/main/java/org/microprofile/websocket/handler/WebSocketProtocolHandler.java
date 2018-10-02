package org.microprofile.websocket.handler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.microprofile.nio.handler.ProtocolHandler;
import org.microprofile.websocket.utils.HttpProtocolUtils;
import org.microprofile.websocket.utils.MessageUtils;

/**
 * @author zhangxdr
 *
 */
public class WebSocketProtocolHandler implements ProtocolHandler {
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
        Session session = (Session) key.attachment();
        if (null == session) {
            session = new Session(socketChannel);
            key.attach(session);
        }
        try {
            if (session.isInitialized()) {
                ByteBuffer lastMessage = session.getLastMessage();
                Message message;
                if (null != lastMessage) {
                    session.setLastMessage(null);
                    ByteBuffer newByteBuffer = ByteBuffer.allocate(lastMessage.limit() + byteBuffer.position());
                    newByteBuffer.put(lastMessage.array());
                    byteBuffer.flip();
                    while (byteBuffer.hasRemaining()) {
                        newByteBuffer.put(byteBuffer.get());
                    }
                    byteBuffer = newByteBuffer;
                }
                message = MessageUtils.processMessage(byteBuffer);
                if (null == message) {
                    session.setLastMessage(byteBuffer);
                } else {
                    if (MessageUtils.isControl(message.getOpCode())) {
                        if (Message.OPCODE_CLOSE == message.getOpCode()) {
                            close(key);
                        } else if (Message.OPCODE_PING == message.getOpCode()) {
                            Message pongMessage = new Message(message.isFin(), message.getRsv(), Message.OPCODE_PONG,
                                    message.getPayload());
                            socketChannel.write(MessageUtils.wrapMessage(pongMessage, false, true));
                        }
                    } else {
                        handler.onMessage(message, session);
                    }
                }
            } else {
                if (server) {
                    HttpProtocolUtils.processProtocol(socketChannel, byteBuffer);
                }
                session.setInitialized(true);
                handler.onOpen(session);
            }
        } catch (IOException e) {
            if (null != session) {
                handler.onClose(session);
            }
            throw new IOException(e);
        }
    }

    @Override
    public void close(SelectionKey key) throws IOException {
        Session session = (Session) key.attachment();
        if (null != session) {
            handler.onClose(session);
        }
    }
}
