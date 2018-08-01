package org.microprofile.nio.handler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;

public interface ProtocolHandler {

    public void read(SelectionKey key, ByteBuffer byteBuffer) throws IOException;

    public void close(SelectionKey key) throws IOException;

}