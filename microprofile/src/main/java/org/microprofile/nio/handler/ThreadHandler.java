package org.microprofile.nio.handler;

import java.io.IOException;
import java.nio.ByteBuffer;

public class ThreadHandler implements Runnable {
    private ProtocolHandler protocolHandler;
    private ChannelContext channel;
    private ByteBuffer byteBuffer;

    public ThreadHandler(ProtocolHandler protocolHandler, ByteBuffer byteBuffer, ChannelContext channel) {
        this.protocolHandler = protocolHandler;
        this.channel = channel;
        this.byteBuffer = byteBuffer;
    }

    @Override
    public void run() {
        try {
            protocolHandler.read(channel.getKey(), byteBuffer);
        } catch (IOException e) {
            if (channel.getKey().channel().isOpen()) {
                try {
                    channel.getSocketChannel().close();
                    channel.getKey().cancel();
                } catch (IOException e1) {
                }
            }
        }
    }
}
