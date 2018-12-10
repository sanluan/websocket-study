package org.microprofile.file.handler;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.microprofile.common.utils.EncodeUtils;
import org.microprofile.common.utils.EventQueue;
import org.microprofile.file.constant.Constants;
import org.microprofile.file.event.EventHandler;
import org.microprofile.file.event.FileEvent;
import org.microprofile.file.event.FileEvent.EventType;
import org.microprofile.file.message.BlockChecksumMessage.BlockChecksum;
import org.microprofile.file.message.FileChecksumMessage.FileChecksum;
import org.microprofile.websocket.handler.Session;

import com.fasterxml.jackson.core.JsonProcessingException;

public class FileEventHandler implements EventHandler {
    protected final Log log = LogFactory.getLog(getClass());
    private static byte[] EMPTY_BYTE = new byte[0];
    private static byte HEADER_FILE_CREATE = EventType.FILE_CREATE.getCode();
    private static byte HEADER_FILE_MODIFY = EventType.FILE_MODIFY.getCode();
    private static byte HEADER_MAX_EVENT = EventType.DIRECTORY_DELETE.getCode();
    private static byte HEADER_FILE_CHECKSUM = (byte) (HEADER_MAX_EVENT + 1);
    private static byte HEADER_BLOCK_CHECKSUM = (byte) (HEADER_MAX_EVENT + 2);
    private static byte HEADER_FILE_CHECKSUM_RESULT = (byte) (HEADER_MAX_EVENT + 3);
    private static byte HEADER_BLOCK_CHECKSUM_RESULT = (byte) (HEADER_MAX_EVENT + 4);
    private static byte FILE_HEADER_LENGTH = 3;

    private Map<String, Session> sessionMap = new HashMap<>();

    private EventQueue<FileEvent> eventQueue;
    private LocalFileAdaptor localFileAdaptor;

    /**
     * @param localFileHandler
     */
    public FileEventHandler(LocalFileAdaptor localFileHandler) {
        this(30, localFileHandler);
    }

    /**
     * @param cacheSize
     * @param localFileAdaptor
     */
    public FileEventHandler(int cacheSize, LocalFileAdaptor localFileAdaptor) {
        super();
        this.eventQueue = new EventQueue<>(cacheSize);
        this.localFileAdaptor = localFileAdaptor;
    }

    @Override
    public void handle(FileEvent event) {
        if (eventQueue.contains(event)) {
            eventQueue.remove(event);
            log.info("skip " + event);
        } else {
            byte code = event.getEventType().getCode();
            log.info("deal " + event);
            if (HEADER_FILE_CREATE == code || HEADER_FILE_MODIFY == code) {
                File file = localFileAdaptor.getFile(event.getFilePath());
                if (Constants.DEFULT_BLOCK_SIZE < event.getFileSize()) {
                    try {
                        sendFileBlock(code, event.getFilePath(), 0, -1, FileUtils.readFileToByteArray(file));
                    } catch (IOException e) {
                    }
                } else {
                    if (HEADER_FILE_MODIFY == code) {
                        List<BlockChecksum> fileList = new LinkedList<>();
                        long blocks = event.getFileSize() / Constants.DEFULT_BLOCK_SIZE;
                        byte[] data = new byte[Constants.DEFULT_BLOCK_SIZE];
                        for (long i = 0; i <= blocks; i++) {
                            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                                if (i == blocks) {
                                    int lastBlockSize = (int) (event.getFileSize() % Constants.DEFULT_BLOCK_SIZE);
                                    if (0 != lastBlockSize) {
                                        byte[] lastBlock = new byte[lastBlockSize];
                                        raf.read(lastBlock);
                                        fileList.add(new BlockChecksum(i, EncodeUtils.md2(lastBlock)));
                                    }
                                } else {
                                    raf.seek(i * Constants.DEFULT_BLOCK_SIZE);
                                    raf.read(data);
                                    fileList.add(new BlockChecksum(i, EncodeUtils.md2(data)));
                                }
                            } catch (FileNotFoundException e) {
                                event.setEventType(EventType.FILE_DELETE);
                                sendEvent(event);
                            } catch (IOException e) {
                            }
                        }
                    } else {
                        long blocks = event.getFileSize() / Constants.DEFULT_BLOCK_SIZE;
                        byte[] data = new byte[Constants.DEFULT_BLOCK_SIZE];
                        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                            for (long i = 0; i <= blocks; i++) {
                                if (i == blocks) {
                                    int lastBlockSize = (int) (event.getFileSize() % Constants.DEFULT_BLOCK_SIZE);
                                    if (0 != lastBlockSize) {
                                        byte[] lastBlock = new byte[lastBlockSize];
                                        raf.read(lastBlock);
                                        sendFileBlock(code, event.getFilePath(), i, Constants.DEFULT_BLOCK_SIZE, lastBlock);
                                    } else if (0 == blocks) {
                                        sendFileBlock(code, event.getFilePath(), 0, -1, EMPTY_BYTE);
                                    }
                                } else {
                                    raf.seek(i * Constants.DEFULT_BLOCK_SIZE);
                                    raf.read(data);
                                    sendFileBlock(code, event.getFilePath(), i, Constants.DEFULT_BLOCK_SIZE, data);
                                }
                            }
                        } catch (FileNotFoundException e) {
                            event.setEventType(EventType.FILE_DELETE);
                            sendEvent(event);
                        } catch (IOException e) {
                        }
                    }
                }
            } else {
                sendEvent(event);
            }
        }
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
        byte[] pathByte = event.getFilePath().getBytes(Constants.DEFAULT_CHARSET);
        byte[] payload = new byte[FILE_HEADER_LENGTH + pathByte.length];
        payload[0] = event.getEventType().getCode();
        System.arraycopy(EncodeUtils.short2Byte((short) pathByte.length), 0, payload, 1, 2);
        System.arraycopy(pathByte, 0, payload, FILE_HEADER_LENGTH, pathByte.length);
        sendToAll(payload);
        log.info("send " + event);
    }

    public void sendBlockchecksumList(String filePath, long blockSize, List<BlockChecksum> blockList) {
        byte[] pathByte = filePath.getBytes(Constants.DEFAULT_CHARSET);
        byte[] payload = new byte[1 + pathByte.length];
        payload[0] = HEADER_FILE_CHECKSUM;
        System.arraycopy(EncodeUtils.short2Byte((short) pathByte.length), 0, payload, 1, 2);
        System.arraycopy(pathByte, 0, payload, FILE_HEADER_LENGTH, pathByte.length);

    }

    public void sendFileBlock(byte code, String filePath, long blockIndex, long blockSize, byte[] data) {
        byte[] pathByte = filePath.getBytes(Constants.DEFAULT_CHARSET);
        byte[] payload = new byte[FILE_HEADER_LENGTH + pathByte.length + 16 + data.length];
        payload[0] = code;
        System.arraycopy(EncodeUtils.short2Byte((short) pathByte.length), 0, payload, 1, 2);
        System.arraycopy(pathByte, 0, payload, FILE_HEADER_LENGTH, pathByte.length);
        System.arraycopy(EncodeUtils.long2Byte(blockIndex), 0, payload, FILE_HEADER_LENGTH + pathByte.length, 8);
        System.arraycopy(EncodeUtils.long2Byte(blockSize), 0, payload, FILE_HEADER_LENGTH + pathByte.length + 8, 8);
        System.arraycopy(data, 0, payload, FILE_HEADER_LENGTH + pathByte.length + 16, data.length);
        sendToAll(payload);
        log.info("send " + filePath + "\tblock index:" + blockIndex);
    }

    public void sendFileChecksumList(List<FileChecksum> fileList) {
        try {
            byte[] data = Constants.objectMapper.writeValueAsBytes(fileList);
            byte[] payload = new byte[1 + data.length];
            payload[0] = HEADER_FILE_CHECKSUM;
            System.arraycopy(data, 0, payload, 1, data.length);
            sendToAll(payload);
        } catch (JsonProcessingException e) {
        }
    }

    @Override
    public void cache(FileEvent event) {
        eventQueue.add(event);
    }

    @Override
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
                    cache(new FileEvent(eventType, filePath, fileSize));
                }
            }
        } else if (HEADER_FILE_CHECKSUM == header) {

        } else if (HEADER_BLOCK_CHECKSUM == header) {

        } else if (HEADER_FILE_CHECKSUM_RESULT == header) {

        } else if (HEADER_BLOCK_CHECKSUM_RESULT == header) {

        }
    }

    @Override
    public void register(Session session) {
        sessionMap.put(session.getId(), session);
    }

    @Override
    public void closing(Session session) {
        sessionMap.remove(session.getId());
    }
}