package org.microprofile.proxy.handler;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.microprofile.common.buffer.MultiByteBuffer;
import org.microprofile.nio.SocketClient;
import org.microprofile.nio.handler.ChannelContext;
import org.microprofile.nio.handler.ProtocolHandler;

public class ServerProtocolHandler implements ProtocolHandler<SocketClient> {
    protected final Log log = LogFactory.getLog(getClass());
    public static byte[] LINE_END = "\n".getBytes();

    @Override
    public void read(ChannelContext<SocketClient> channelContext, MultiByteBuffer multiByteBuffer) throws IOException {
        multiByteBuffer.mark();
        byte[] b = new byte[multiByteBuffer.remaining()];
        multiByteBuffer.get(b);
        log.info(new String(b));
        multiByteBuffer.reset();
        SocketClient socketClient = channelContext.getAttachment();
        if (null == socketClient) {
            StringBuilder hostBuilder = new StringBuilder();
            int status = 1;
            multiByteBuffer.mark();
            while (multiByteBuffer.hasRemaining() && 4 != status) {
                char c = (char) multiByteBuffer.get();
                if (1 == status) {
                    if (' ' == c) {
                        status++;// 开始读取host
                    }
                } else if (2 == status) {
                    if (' ' == c) {
                        status++;// 协议信息
                    } else {
                        hostBuilder.append(c);
                    }
                } else if (3 == status) {
                    if ('\n' == c) { // 结束读取
                        status++;
                    }
                }
            }
            while (multiByteBuffer.hasRemaining() && 5 != status) {
                char c = (char) multiByteBuffer.get();
                if ('\r' == c && '\n' == (char) multiByteBuffer.get() && '\r' == (char) multiByteBuffer.get()
                        && '\n' == (char) multiByteBuffer.get()) {
                    status++;
                }
            }
            if (5 == status) {
                String hostString = hostBuilder.toString();
                String[] hostParams = hostString.split(":");
                String host = hostParams[0].trim();
                int port = hostParams.length == 1 ? 80 : Integer.valueOf(hostParams[1].trim());
                socketClient = new SocketClient(host, port, null, new ProxyProtocolHandler(channelContext));
                socketClient.asyncListen();
                channelContext.setAttachment(socketClient);
                socketClient.sendMessage("HTTP/1.1 200 Connection established\r\nPoryx-Agent: test1.0\r\n\r\n");
            } else {
                log.info("reset " + status + " " + hostBuilder.toString());
                multiByteBuffer.reset();
            }
        } else {
            socketClient.sendMessage(multiByteBuffer);
        }
    }

    @Override
    public void close(ChannelContext<SocketClient> channelContext) throws IOException {
        SocketClient socketClient = channelContext.getAttachment();
        if (null != socketClient && socketClient.isOpen()) {
            socketClient.close();
        }
    }

}
