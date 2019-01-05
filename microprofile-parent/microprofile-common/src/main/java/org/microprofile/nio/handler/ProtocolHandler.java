package org.microprofile.nio.handler;

import java.io.IOException;

import org.microprofile.common.buffer.MultiByteBuffer;

public interface ProtocolHandler<T> {

    public void read(ChannelContext<T> channelContext, MultiByteBuffer multiByteBuffer) throws IOException;

    public void close(ChannelContext<T> channelContext) throws IOException;

}