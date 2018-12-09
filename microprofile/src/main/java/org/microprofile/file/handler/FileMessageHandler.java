package org.microprofile.file.handler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.microprofile.common.utils.EncodeUtils;
import org.microprofile.file.constant.Constants;
import org.microprofile.file.event.FileEvent;
import org.microprofile.websocket.handler.Message;
import org.microprofile.websocket.handler.MessageHandler;
import org.microprofile.websocket.handler.Session;

public class FileMessageHandler implements MessageHandler {

    private Map<String, Session> sessionMap = new HashMap<>();
    private LocalFileHandler localFileHandler;

    public FileMessageHandler(LocalFileHandler localFileHandler) {
        super();
        this.localFileHandler = localFileHandler;
    }

    @Override
    public void onMessage(Message message, Session session) throws IOException {
        byte[] payload = message.getPayload();
        if (payload.length > 0) {
            byte header = payload[0];
            int start = 1;
            String filePath = null;
            if (header <= 4 && payload.length > 3) {
                short length = EncodeUtils.bype2Short(Arrays.copyOfRange(payload, start, 3));
                start = 3 + length;
                if (start <= payload.length) {
                    filePath = new String(Arrays.copyOfRange(payload, 3, start));
                    long index = 0;
                    long blockSize = -1;
                    if (0 == header || 1 == header) {
                        index = EncodeUtils.bype2Long(Arrays.copyOfRange(payload, start, start + 8));
                        blockSize = EncodeUtils.bype2Long(Arrays.copyOfRange(payload, start + 8, start + 16));
                    }
                    switch (header) {
                    case 0:
                        if (-1 == blockSize) {
                            localFileHandler.createFile(filePath, payload, start);
                        } else {
                            localFileHandler.modifyFile(filePath, index, blockSize, payload, start + 16);
                        }
                        break;
                    case 1:
                        localFileHandler.modifyFile(filePath, index, blockSize, payload, start + 16);
                        break;
                    case 2:
                        localFileHandler.deleteFile(filePath);
                        break;
                    case 3:
                        localFileHandler.createDirectory(filePath);
                        break;
                    case 4:
                        localFileHandler.deleteDirectory(filePath);
                        break;
                    }
                }
            } else if (header > 5) {

            }
        }
    }

    @Override
    public void onOpen(Session session) throws IOException {
        sessionMap.put(session.getId(), session);
    }

    @Override
    public void onClose(Session session) throws IOException {
        sessionMap.remove(session.getId());
    }

    private List<String> sendToAll(byte[] data) {
        List<String> failureList = null;
        for (Session session : sessionMap.values()) {
            try {
                session.sendByte(data);
            } catch (IOException e) {
                if (null == failureList) {
                    failureList = new ArrayList<>();
                }
                failureList.add(session.getId());
            }
        }
        return failureList;
    }

    public void sendEvent(FileEvent event) {
        byte[] payload = event.getFilePath().getBytes(Constants.DEFAULT_CHARSET);
        byte[] data = new byte[payload.length + 1];
        data[0] = event.getEventType().getCode();
        System.arraycopy(payload, 0, data, 1, data.length);
        sendToAll(data);
    }

    public void sendFileBlockchecksumList(FileEvent event) {
        // TODO
    }

    public void sendFileBlock(byte code, String filePath, long blockIndex, long blockSize, byte[] data) {
        byte[] pathByte = filePath.getBytes(Constants.DEFAULT_CHARSET);
        byte[] payload = new byte[1 + pathByte.length + 16 + data.length];
        payload[0] = code;
        System.arraycopy(pathByte, 0, payload, 1, pathByte.length);
        System.arraycopy(EncodeUtils.long2Byte(blockIndex), 0, payload, pathByte.length + 1, 8);
        System.arraycopy(EncodeUtils.long2Byte(blockSize), 0, payload, pathByte.length + 9, 16);
        System.arraycopy(payload, 0, data, pathByte.length + 17, data.length);
        sendToAll(payload);
    }

    public void sendFileChecksumList(FileEvent event) {
        // TODO
    }
}