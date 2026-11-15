package org.microprofile.websocket.handler;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.microprofile.common.buffer.MultiByteBuffer;
import org.microprofile.common.constant.Constants;
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
    public void read(ChannelContext<WebSocketFrame> channelContext, MultiByteBuffer multiByteBuffer) throws IOException {
        WebSocketFrame webSocketFrame = channelContext.getAttachment();
        if (null == webSocketFrame) {
            channelContext.setAttachment(webSocketFrame = new WebSocketFrame());
        }
        try {
            if (!webSocketFrame.isInitialized()) {
                if (!HttpProtocolUtils.isEnough(multiByteBuffer)) {
                    if (multiByteBuffer.remaining() > maxHeaderLength) {
                        channelContext.close();
                    }
                    return;
                }
                Session session;
                if (server) {
                    session = HttpProtocolUtils.processServerProtocol(channelContext, multiByteBuffer);
                } else {
                    session = HttpProtocolUtils.processClientProtocol(channelContext, multiByteBuffer);
                }
                if (null == session) {
                    channelContext.close();
                    return;
                }
                webSocketFrame.setSession(session);
                webSocketFrame.setInitialized(true);
                handler.onOpen(webSocketFrame.getSession());
                // 握手报文之后可能紧跟着同一批到达的数据帧，需要继续解析
            }
            for (Message message = MessageUtils.processMessage(multiByteBuffer,
                    channelContext); null != message; message = MessageUtils.processMessage(multiByteBuffer, channelContext)) {
                if (!processMessage(channelContext, webSocketFrame, message)) {
                    return;
                }
            }
        } catch (IOException e) {
            channelContext.close();
        }
    }

    /**
     * @param channelContext
     * @param webSocketFrame
     * @param message
     * @return false 表示连接已关闭，不需要继续解析
     * @throws IOException
     */
    private boolean processMessage(ChannelContext<WebSocketFrame> channelContext, WebSocketFrame webSocketFrame, Message message)
            throws IOException {
        if (MessageUtils.isControl(message.getOpCode())) {
            if (Message.OPCODE_CLOSE == message.getOpCode()) {
                channelContext.close();
                return false;
            } else if (Message.OPCODE_PING == message.getOpCode()) {
                Message pongMessage = new Message(true, 0, Message.OPCODE_PONG, message.getPayload());
                // 必须走 ChannelContext.write，SSL 通道下才能正确封装，客户端还需要掩码
                channelContext.write(MessageUtils.wrapMessage(pongMessage, !server, true));
            } else if (Message.OPCODE_PONG == message.getOpCode()) {
                // 对端的心跳应答，直接忽略
            } else {
                channelContext.close();
                return false;
            }
            return true;
        }
        if (!message.isFin()) {
            if (0 == webSocketFrame.getCachedMessageLength()) {
                // 首片记录原始 opcode，后续分片是 PART
                webSocketFrame.setCachedOpCode(message.getOpCode());
            }
            webSocketFrame.addCachedMessage(message.getPayload());
            return true;
        }
        byte opCode = message.getOpCode();
        byte[] payload = message.getPayload();
        if (0 != webSocketFrame.getCachedMessageLength()) {
            if (Message.OPCODE_PART != opCode) {
                // 分片未结束又收到新的消息类型，属于协议错误
                webSocketFrame.clearCachedMessageList();
                channelContext.close();
                return false;
            }
            byte cachedOpCode = webSocketFrame.getCachedOpCode();
            ByteBuffer newByteBuffer = ByteBuffer.allocate(webSocketFrame.getCachedMessageLength() + payload.length);
            for (byte[] cachedMessage : webSocketFrame.getCachedMessageList()) {
                newByteBuffer.put(cachedMessage);
            }
            newByteBuffer.put(payload);
            webSocketFrame.clearCachedMessageList();
            opCode = cachedOpCode;
            payload = newByteBuffer.array();
        }
        if (Message.OPCODE_BYTE == opCode) {
            handler.onMessage(payload, webSocketFrame.getSession());
        } else if (Message.OPCODE_STRING == opCode) {
            handler.onMessage(new String(payload, Constants.DEFAULT_CHARSET), webSocketFrame.getSession());
        } else {
            channelContext.close();
            return false;
        }
        return true;
    }

    @Override
    public void close(ChannelContext<WebSocketFrame> channelContext) throws IOException {
        WebSocketFrame frame = channelContext.getAttachment();
        if (null != frame && frame.isInitialized()) {
            handler.onClose(frame.getSession());
        }
    }
}
