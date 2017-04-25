package org.microprofile.websocket.handler;

import java.io.IOException;

public interface MessageHandler {
	public void onMessage(Message message, Session session) throws IOException;

	public void onOpen(Session session) throws IOException;
	
	public void onClose(Session session) throws IOException;
}