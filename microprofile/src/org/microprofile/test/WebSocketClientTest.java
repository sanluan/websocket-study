package org.microprofile.test;

import java.io.IOException;
import java.util.Scanner;

import org.microprofile.websocket.WebSocketClient;
import org.microprofile.websocket.handler.Message;
import org.microprofile.websocket.handler.MessageHandler;
import org.microprofile.websocket.handler.Session;

public class WebSocketClientTest {

	public static void main(String[] args) throws InterruptedException {
		try {
			WebSocketClient ws = new WebSocketClient("localhost", 1000, new ClientMessageHandler());
			System.out.println("启动。。。");
			new WebSocketClientThread(ws).start();
			System.out.println("please input you message,quit to exit");
			Scanner in = new Scanner(System.in);
			while (true) {
				String message = in.nextLine();
				if (message.equals("quit")) {
					in.close();
					ws.close();
					break;
				}
				ws.sendString(message);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

}

class WebSocketClientThread extends Thread {
	private WebSocketClient ws;

	public WebSocketClientThread(WebSocketClient ws) {
		this.ws = ws;
	}

	public void run() {
		try {
			ws.listen();
		} catch (IOException e) {
		}
	}
}

class ClientMessageHandler implements MessageHandler {

	@Override
	public void onMessage(Message message, Session session) throws IOException {
		String str = new String(message.getPayload());
		System.out.println(str);
	}

	@Override
	public void onOpen(Session session) throws IOException {
	}

	@Override
	public void onClose(Session session) throws IOException {
	}

}
