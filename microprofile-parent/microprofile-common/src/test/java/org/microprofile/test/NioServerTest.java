package org.microprofile.test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import org.microprofile.common.buffer.MultiByteBuffer;
import org.microprofile.nio.SocketServer;
import org.microprofile.nio.handler.ChannelContext;
import org.microprofile.nio.handler.ProtocolHandler;

public class NioServerTest {

    public static void main(String[] arg) throws IOException {
        SocketServer socketServer = new SocketServer(1000, Executors.newFixedThreadPool(20), new NioServerProtocolHandler(),
                100 * 1024);
        socketServer.listen();
        socketServer.close();
    }
}

class NioServerProtocolHandler implements ProtocolHandler<Object> {
    Map<ChannelContext<Object>, State> map = new HashMap<>();

    @Override
    public void read(ChannelContext<Object> channelContext, MultiByteBuffer byteBuffer) throws IOException {
        State state = map.get(channelContext);
        if (null == state) {
            state = new State();
            state.start = System.currentTimeMillis();
            map.put(channelContext, state);
        }
        int r = byteBuffer.remaining();
        byte[] dst = new byte[r];
        byteBuffer.get(dst);
        for (int i = 0; i < r; i++) {
            if (state.last != dst[i]) {
                state.last = dst[i];
                System.out.println("error");
            }
            state.last++;
            if (state.last == 125) {
                state.last = 0;
                state.n++;
                if (state.n % 100000 == 0) {
                    System.out.println(state.n);
                    if (1000000 == state.n) {
                        System.out.println(System.currentTimeMillis() - state.start);
                    }
                }
            }
        }
    }

    @Override
    public void close(ChannelContext<Object> channelContext) throws IOException {

    }

    public class State {
        int last = 0;
        int n = 0;
        long start = 0;
    }
}