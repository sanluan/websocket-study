package org.microprofile.nio.handler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;

public class ThreadHandler implements Runnable {
	private ProtocolHandler protocolHandler;
	private SelectionKey key;
	private ByteBuffer byteBuffer;

	public ThreadHandler(ProtocolHandler protocolHandler, ByteBuffer byteBuffer, SelectionKey key) {
		this.protocolHandler = protocolHandler;
		this.key = key;
		this.byteBuffer = byteBuffer;
	}

	@Override
	public void run() {
		try {
			protocolHandler.read(key, byteBuffer);
		} catch (IOException e) {
		}
	}
}
