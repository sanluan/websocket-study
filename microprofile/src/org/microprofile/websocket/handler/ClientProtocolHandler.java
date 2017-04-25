package org.microprofile.websocket.handler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.microprofile.nio.handler.ProtocolHandler;
import org.microprofile.websocket.utils.MessageUtils;

/**
 * @author zhangxdr
 *
 */
public class ClientProtocolHandler implements ProtocolHandler {
	private MessageHandler handler;

	public ClientProtocolHandler(MessageHandler handler) {
		this.handler = handler;
	}

	@Override
	public void read(SelectionKey key, ByteBuffer byteBuffer) throws IOException {
		SocketChannel socketChannel = (SocketChannel) key.channel();
		Session session = (Session) key.attachment();
		if (null == session) {
			session = new Session(socketChannel);
			key.attach(session);
			handler.onOpen(session);
		} else {
			Message message = MessageUtils.processMessage(byteBuffer);
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
	}

	@Override
	public void close(SelectionKey key) throws IOException {
		Session session = (Session) key.attachment();
		if (null != session) {
			handler.onClose(session);
		}
	}
}
