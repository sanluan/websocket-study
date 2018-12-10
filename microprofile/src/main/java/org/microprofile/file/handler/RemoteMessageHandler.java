package org.microprofile.file.handler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.microprofile.common.utils.EncodeUtils;
import org.microprofile.file.constant.Constants;
import org.microprofile.file.event.EventHandler;
import org.microprofile.file.event.FileEvent;
import org.microprofile.file.event.FileEvent.EventType;
import org.microprofile.file.message.BlockChecksumMessage.BlockChecksum;
import org.microprofile.file.message.FileChecksumMessage.FileChecksum;
import org.microprofile.websocket.handler.MessageHandler;
import org.microprofile.websocket.handler.Session;

import com.fasterxml.jackson.core.JsonProcessingException;

public class RemoteMessageHandler implements MessageHandler {
    protected final Log log = LogFactory.getLog(getClass());
    private Map<String, Session> sessionMap = new HashMap<>();

    private static byte HEADER_MAX_EVENT = EventType.DIRECTORY_DELETE.getCode();
    private static byte HEADER_FILE_CHECKSUM = 10;
    private static byte HEADER_BLOCK_CHECKSUM = 11;
    private static byte HEADER_FILE_CHECKSUM_RESULT = 12;
    private static byte HEADER_BLOCK_CHECKSUM_RESULT = 13;
    private static byte FILE_HEADER_LENGTH = 3;

    private EventHandler eventHandler;
    private LocalFileAdaptor localFileAdaptor;

    /**
     * @param eventHandler
     * @param localFileAdaptor
     */
    public RemoteMessageHandler(EventHandler eventHandler, LocalFileAdaptor localFileAdaptor) {
        super();
        this.eventHandler = eventHandler;
        this.localFileAdaptor = localFileAdaptor;
    }

    @Override
    public void onMessage(String message, Session session) throws IOException {
        if (0 < message.length()) {
            handle(message.getBytes(Constants.DEFAULT_CHARSET), session);
        }
    }

    @Override
    public void onMessage(byte[] message, Session session) throws IOException {
        if (0 < message.length) {
            handle(message, session);
        }
    }

    public void handle(byte[] payload, Session session) {
        byte header = payload[0];
        String filePath = null;
        log.info("receive " + header);
        if (0 <= header && HEADER_MAX_EVENT >= header && FILE_HEADER_LENGTH < payload.length) {
            short filePathLength = EncodeUtils.bype2Short(Arrays.copyOfRange(payload, 1, FILE_HEADER_LENGTH));
            int startIndex = FILE_HEADER_LENGTH + filePathLength;
            EventType eventType = null;
            if (startIndex <= payload.length) {
                filePath = new String(Arrays.copyOfRange(payload, FILE_HEADER_LENGTH, startIndex));
                long fileSize = 0;
                long index = 0;
                long blockSize = -1;
                if (0 == header || 1 == header) {
                    index = EncodeUtils.bype2Long(Arrays.copyOfRange(payload, startIndex, startIndex + 8));
                    blockSize = EncodeUtils.bype2Long(Arrays.copyOfRange(payload, startIndex + 8, startIndex + 16));
                    startIndex += 16;
                }
                switch (header) {
                case 0:
                    if (-1 == blockSize) {
                        fileSize = localFileAdaptor.createFile(filePath, payload, startIndex);
                        eventType = EventType.FILE_CREATE;

                    } else {
                        fileSize = localFileAdaptor.modifyFile(filePath, index, blockSize, payload, startIndex);
                        eventType = EventType.FILE_MODIFY;
                    }
                    break;
                case 1:
                    fileSize = localFileAdaptor.modifyFile(filePath, index, blockSize, payload, startIndex);
                    eventType = EventType.FILE_MODIFY;
                    break;
                case 2:
                    fileSize = localFileAdaptor.deleteFile(filePath);
                    eventType = EventType.FILE_DELETE;
                    break;
                case 3:
                    fileSize = localFileAdaptor.createDirectory(filePath);
                    eventType = EventType.DIRECTORY_CREATE;
                    break;
                case 4:
                    fileSize = localFileAdaptor.deleteDirectory(filePath);
                    eventType = EventType.DIRECTORY_DELETE;
                    break;
                }
                if (null != eventType) {
                    eventHandler.cache(new FileEvent(eventType, filePath, fileSize));
                }
            }
        } else if (HEADER_FILE_CHECKSUM == header) {

        } else if (HEADER_BLOCK_CHECKSUM == header) {

        } else if (HEADER_FILE_CHECKSUM_RESULT == header) {

        } else if (HEADER_BLOCK_CHECKSUM_RESULT == header) {

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
                log.error("send to " + session.getId());
            }
        }
        return failureList;
    }

    public void sendEvent(FileEvent event) {
        byte[] pathByte = getFilePathByte(event.getFilePath());
        byte[] payload = new byte[FILE_HEADER_LENGTH + pathByte.length];
        setHeader(event.getEventType().getCode(), pathByte, payload);
        sendToAll(payload);
        log.info("sent " + event);
    }

    public void sendBlockchecksumList(String filePath, long blockSize, List<BlockChecksum> blockList) {
        try {
            byte[] pathByte = getFilePathByte(filePath);
            int headerLength = FILE_HEADER_LENGTH + pathByte.length;
            byte[] data = Constants.objectMapper.writeValueAsBytes(blockList);
            byte[] payload = new byte[headerLength + data.length];

            setHeader(HEADER_BLOCK_CHECKSUM, pathByte, payload);

            System.arraycopy(EncodeUtils.long2Byte(blockSize), 0, payload, headerLength, 8);
            System.arraycopy(data, 0, payload, headerLength + 8, data.length);
            sendToAll(payload);
            log.info("sent file blocks checksum of:" + filePath);
        } catch (JsonProcessingException e) {
        }
    }

    public void sendFileBlock(byte code, String filePath, long blockIndex, long blockSize, byte[] data) {
        byte[] pathByte = filePath.getBytes(Constants.DEFAULT_CHARSET);
        int headerLength = FILE_HEADER_LENGTH + pathByte.length;
        byte[] payload = new byte[headerLength + 16 + data.length];

        setHeader(HEADER_BLOCK_CHECKSUM, pathByte, payload);

        System.arraycopy(EncodeUtils.long2Byte(blockIndex), 0, payload, headerLength, 8);
        System.arraycopy(EncodeUtils.long2Byte(blockSize), 0, payload, headerLength + 8, 8);

        System.arraycopy(data, 0, payload, headerLength + 16, data.length);
        sendToAll(payload);
        log.info("sent file blocks of:" + filePath);
    }

    public void sendFileChecksumList(List<FileChecksum> fileList) {
        try {
            byte[] data = Constants.objectMapper.writeValueAsBytes(fileList);
            byte[] payload = new byte[1 + data.length];
            payload[0] = HEADER_FILE_CHECKSUM;
            System.arraycopy(data, 0, payload, 1, data.length);
            sendToAll(payload);
            log.info("sent file checksum list:" + fileList);
        } catch (JsonProcessingException e) {
        }
    }

    private byte[] getFilePathByte(String filePath) {
        return filePath.getBytes(Constants.DEFAULT_CHARSET);
    }

    private void setHeader(byte code, byte[] pathByte, byte[] payload) {
        payload[0] = code;
        System.arraycopy(EncodeUtils.short2Byte((short) pathByte.length), 0, payload, 1, 2);
        System.arraycopy(pathByte, 0, payload, FILE_HEADER_LENGTH, pathByte.length);
    }
}
