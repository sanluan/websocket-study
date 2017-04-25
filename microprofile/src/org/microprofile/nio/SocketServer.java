package org.microprofile.nio;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.microprofile.nio.handler.ProtocolHandler;
import org.microprofile.nio.handler.SocketProcesser;

/**
 * @author zhangxdr
 *
 */
public class SocketServer extends SocketProcesser implements Closeable {
	private ServerSocketChannel serverSocketChannel;
	private String host;
	private int port;

	public SocketServer(int port, ExecutorService pool, ProtocolHandler protocolHandler) throws IOException {
		this(null, port, pool, protocolHandler);
	}

	public SocketServer(String host, int port, ExecutorService pool, ProtocolHandler protocolHandler)
			throws IOException {
		super(pool, protocolHandler);
		this.host = host;
		this.port = port;
		this.serverSocketChannel = ServerSocketChannel.open();
	}

	public void listen() throws IOException {
		ServerSocket serverSocket = serverSocketChannel.socket();
		serverSocket.bind(null == host ? new InetSocketAddress(port) : new InetSocketAddress(host, port));
		serverSocketChannel.configureBlocking(false);
		if (null == pool) {
			pool = Executors.newFixedThreadPool(1);
		}
		serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
		while (serverSocketChannel.isOpen()) {
			polling();
		}
	}

	@Override
	public void close() throws IOException {
		if (serverSocketChannel.isOpen()) {
			serverSocketChannel.close();
		}
		super.close();
	}

	public ProtocolHandler getProtocolHandler() {
		return protocolHandler;
	}

	public String getHost() {
		return host;
	}

	public int getPort() {
		return port;
	}

}