package com.publiccms.message;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.microprofile.common.constant.Constants;
import org.microprofile.message.ThinMessageServer;
import org.microprofile.websocket.handler.MessageHandler;
import org.microprofile.websocket.handler.Session;

public class MyMessageHandler implements MessageHandler {
    private ThinMessageServer server;
    Map<String, User> map = new HashMap<>();

    /**
     * @param server
     * 
     */
    public MyMessageHandler(ThinMessageServer server) {
        this.server = server;
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
                if ("admin".equals(message)) {
                    User user = map.get(session.getId());
                    if (null != user) {
                        user.setMaster(true);
                        session.sendString("s:login success!");
                    }
                }
                break;
            }
            case 'l': {
                User user = map.get(session.getId());
                if (null != user && user.isMaster()) {
                    String[] paths = message.split(",");
                    if (1 == paths.length) {
                        server.load(paths[0]);
                    } else if (2 == paths.length) {
                        server.load(paths[0], paths[1]);
                    } else {
                        session.sendString("s:error path");
                    }
                } else {
                    session.sendString("s:no auth");
                }
                break;
            }
            case 'p': {
                session.sendString(message);
                break;
            }
            case 'd': {
                User user = map.get(session.getId());
                if (null != user && user.isMaster()) {
                    String[] paths = message.split(",");
                    if (1 == paths.length) {
                        server.unLoad(paths[0]);
                    } else {
                        session.sendString("s:error path");
                    }
                } else {
                    session.sendString("s:no auth");
                }
                break;
            }
            case 'b': {
                User user = map.get(session.getId());
                if (null != user && user.isMaster()) {
                    user = map.remove(message);
                    if (null != user) {
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
                session.sendString("s:" + map.keySet().toString());
                break;
            }
            case 'n': {
                User user = map.get(session.getId());
                if (null != user && null != message && 0 < message.length() && 100 > message.length()) {
                    user.setNickName(message);
                    session.sendString("s:operate success!");
                } else {
                    session.sendString("s:error");
                }
                break;
            }
            case 'g': {
                Collection<User> users = map.values();
                for (User u : users) {
                    if (u.getSession() != session) {
                        u.getSession().sendString("g:" + session.getId() + ":" + message);
                    }
                }
                break;
            }
            case 't': {
                int index = message.indexOf(":");
                if (0 < index) {
                    User user = map.get(message.substring(0, index));
                    if (null != user) {
                        user.getSession().sendString("u:" + session.getId() + ":" + message.substring(index + 1));
                    } else {
                        session.sendString("s:not online:" + message);
                    }
                }
                break;
            }
            default:
                session.sendString("s:error message");
            }
        }
    }

    public void onOpen(Session session) throws IOException {
        User user = new User();
        user.setSession(session);
        map.put(session.getId(), user);
    }

    public void onClose(Session session) throws IOException {
        map.remove(session.getId());
    }

    class User {
        private Session session;
        private String nickName;
        boolean master;

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
         * @return the master
         */
        public boolean isMaster() {
            return master;
        }

        /**
         * @param master
         *            the master to set
         */
        public void setMaster(boolean master) {
            this.master = master;
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
