package com.publiccms.message;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.microprofile.common.constant.Constants;
import org.microprofile.message.ThinMessageServer;
import org.microprofile.websocket.handler.MessageHandler;
import org.microprofile.websocket.handler.Session;

public class MyMessageHandler implements MessageHandler {
    protected final Log log = LogFactory.getLog(getClass());
    @SuppressWarnings("unused")
    private ThinMessageServer server;
    Map<Integer, User> userMap = new HashMap<>();
    private ConcurrentLinkedQueue<Integer> userIdQueue = new ConcurrentLinkedQueue<>();
    private Map<String, User> sessionMap = new HashMap<>();
    private Session adminSession;
    private String password;
    private int i;

    public int getNextUserId() {
        Integer userId = userIdQueue.poll();
        if (null == userId) {
            return ++i;
        }
        return userId;
    }

    /**
     * @param server
     * 
     */
    public MyMessageHandler(ThinMessageServer server) {
        this.server = server;
        password = UUID.randomUUID().toString();
        log.warn("password:" + password);
    }

    public void onMessage(byte[] message, Session session) throws IOException {
        onMessage(new String(message, Constants.DEFAULT_CHARSET), session);
    }

    public void onMessage(String message, Session session) throws IOException {
        if (1 < message.length() && ':' == message.charAt(1)) {
            char command = message.charAt(0);
            message = message.substring(2);
            switch (command) {
            case 'a': {
                if (password.equals(message)) {
                    adminSession = session;
                    session.sendString("s:login success!");
                }
                break;
            }
            case 'p': {
                break;
            }
            case 'b': {
                if (null != adminSession && adminSession == session) {
                    User user = sessionMap.remove(session.getId());
                    if (null != user) {
                        userMap.remove(user.getId());
                        user.getSession().close();
                        session.sendString("s:bye to:" + message);
                    } else {
                        session.sendString("s:not online:" + message);
                    }
                } else {
                    session.sendString("s:no auth");
                }
                break;
            }
            case 'u': {
                if (null != adminSession && adminSession == session) {
                    session.sendString("s:" + userMap.keySet().toString());
                } else {
                    session.sendString("s:no auth");
                }
                break;
            }
            case 'n': {
                User user = sessionMap.get(session.getId());
                if (null != user && null != message && 0 < message.length() && 100 > message.length()) {
                    user.setNickName(message);
                    session.sendString("s:operate success!");
                } else {
                    session.sendString("s:error");
                }
                break;
            }
            default:
                session.sendString("s:error message");
            }
        } else if (null != adminSession) {
            User user = sessionMap.get(session.getId());
            adminSession.sendString(user.getNickName() + ",编号" + user.getId() + " 说:" + message);
        } else {
            session.sendString("客服不在线哦");
        }
    }

    public void onOpen(Session session) throws IOException {
        User user = new User();
        user.setSession(session);
        user.setId(getNextUserId());
        sessionMap.put(session.getId(), user);
        userMap.put(user.getId(), user);
    }

    public void onClose(Session session) throws IOException {
        User user = sessionMap.remove(session.getId());
        userMap.remove(user.getId());
        userIdQueue.add(user.getId());
        if (null != adminSession && adminSession == session) {
            adminSession = null;
        }
    }

    class User {
        private Session session;
        private String nickName;
        private int id;

        /**
         * @return the i
         */
        public int getId() {
            return id;
        }

        /**
         * @param id
         *            the id to set
         */
        public void setId(int id) {
            this.id = id;
        }

        /**
         * @return the session
         */
        public Session getSession() {
            return session;
        }

        /**
         * @param session
         *            the session to set
         */
        public void setSession(Session session) {
            this.session = session;
        }

        /**
         * @return the nickName
         */
        public String getNickName() {
            return nickName;
        }

        /**
         * @param nickName
         *            the nickName to set
         */
        public void setNickName(String nickName) {
            this.nickName = nickName;
        }
    }
}
