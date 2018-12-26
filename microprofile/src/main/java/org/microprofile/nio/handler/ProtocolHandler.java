package org.microprofile.nio.handler;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface ProtocolHandler<T> {

    public void read(ChannelContext<T> channelContext, ByteBuffer byteBuffer) throws IOException;

    public void close(ChannelContext<T> channelContext) throws IOException;

}