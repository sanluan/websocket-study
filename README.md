# websocket-study
websocket-study为websocket学习项目,基于nio实现的socket协议服务端、客户端，websocket服务端、客户端，文件双向同步服务，websocket容器等

已实现功能：
1. nio server
1. nio client
1. websocket server
1. websocket client
1. 文件双向同步
1. 多应用websocket容器
1. nio client ssl
1. wss websocket client

开发中：

1. nio server ssl
1. wss websocket server

websocket服务端实现示例：


	import java.io.IOException;
	import java.util.HashMap;
	import java.util.Map;

	import org.apache.commons.logging.Log;
	import org.apache.commons.logging.LogFactory;
	import org.microprofile.websocket.WebSocketServer;
	import org.microprofile.websocket.handler.MessageHandler;
	import org.microprofile.websocket.handler.Session;

	public class WebSocketServerTest implements MessageHandler {
	    protected static final Log log = LogFactory.getLog(WebSocketServerTest.class);
	    Map<String, Session> map = new HashMap<>();

	    public static void main(String[] args) throws InterruptedException {
		try {
		    WebSocketServer ws = new WebSocketServer(1000, 20, new WebSocketServerTest(), 1000);
		    log.info("启动。。。");
		    ws.asyncListen();
		    Thread.sleep(1000 * 1000);
		    ws.close();
		} catch (IOException e) {
		}
	    }

	    @Override
	    public void onMessage(byte[] message, Session session) throws IOException {
		onMessage(new String(message), session);
	    }

	    @Override
	    public void onMessage(String message, Session session) throws IOException {
		session.sendString("receive:" + message);
		log.info(message);
	    }

	    @Override
	    public void onOpen(Session session) throws IOException {
		session.sendString("welcome");
		map.put(session.getId(), session);
		log.info(session.getId() + "\t connected!");
	    }

	    @Override
	    public void onClose(Session session) throws IOException {
		session.sendString("bye");
		map.remove(session.getId());
		log.info(session.getId() + "\t closed!");
	    }

	}


websocket客户端实现示例：


	import java.io.IOException;
	import java.net.URISyntaxException;
	import java.security.KeyManagementException;
	import java.security.NoSuchAlgorithmException;

	import org.apache.commons.logging.Log;
	import org.apache.commons.logging.LogFactory;
	import org.microprofile.websocket.WebSocketClient;
	import org.microprofile.websocket.handler.MessageHandler;
	import org.microprofile.websocket.handler.Session;

	public class WebSocketClientTest implements MessageHandler {
	    protected static final Log log = LogFactory.getLog(WebSocketClientTest.class);

	    public static void main(String[] args) throws InterruptedException, URISyntaxException {
		try {
		    WebSocketClient ws = new WebSocketClient("ws://127.0.0.1/message/test/", new WebSocketClientTest());
		    log.info("启动。。。");
		    ws.asyncListen();
		    while (!ws.isOpen()) {
			Thread.sleep(100);
		    }
		    ws.sendString("u:");
		    Thread.sleep(10000);
		    ws.close();
		} catch (IOException e) {
		    e.printStackTrace();
		} catch (NoSuchAlgorithmException e) {
		    e.printStackTrace();
		} catch (KeyManagementException e) {
		    e.printStackTrace();
		}
	    }

	    @Override
	    public void onMessage(byte[] message, Session session) throws IOException {
		onMessage(new String(message), session);
	    }

	    @Override
	    public void onMessage(String message, Session session) throws IOException {
		log.info("receive:" + message);
	    }

	    @Override
	    public void onOpen(Session session) throws IOException {
		log.info(session.getId() + "\t connected!");
	    }

	    @Override
	    public void onClose(Session session) throws IOException {
		log.info(session.getId() + "\t closed!");
	    }

	}