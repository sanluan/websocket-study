package org.microprofile.proxy.handler;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.microprofile.common.buffer.MultiByteBuffer;
import org.microprofile.nio.SocketClient;
import org.microprofile.nio.handler.ChannelContext;
import org.microprofile.nio.handler.ProtocolHandler;

public class ProxyProtocolHandler implements ProtocolHandler<Object> {
    protected final Log log = LogFactory.getLog(getClass());
    ChannelContext<SocketClient> serverChannelContext;

    public ProxyProtocolHandler(ChannelContext<SocketClient> channelContext) {
        this.serverChannelContext = channelContext;
    }

    @Override
    public void read(ChannelContext<Object> channelContext, MultiByteBuffer multiByteBuffer) throws IOException {
        multiByteBuffer.mark();
        byte[] b = new byte[multiByteBuffer.remaining()];
        multiByteBuffer.get(b);
        log.info(new String(b));
        multiByteBuffer.reset();
        serverChannelContext.write(multiByteBuffer);

    }

    @Override
    public void close(ChannelContext<Object> channelContext) throws IOException {
        serverChannelContext.close();
    }

}
