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
import org.microprofile.common.utils.EncodeUtils;
import org.microprofile.common.utils.EventQueue;
import org.microprofile.file.constant.Constants;
import org.microprofile.file.event.EventHandler;
import org.microprofile.file.event.FileEvent;
import org.microprofile.file.event.FileEvent.EventType;
import org.microprofile.file.message.FileBlockChecksumListMessage.FileBlockChecksum;
import org.microprofile.websocket.handler.Message;
import org.microprofile.websocket.handler.MessageHandler;
import org.microprofile.websocket.handler.Session;

public class FileEventHandler implements EventHandler, MessageHandler {
    private EventQueue<FileEvent> eventQueue;
    private Map<String, Session> sessionMap = new HashMap<>();
    private LocalFileAdaptor localFileHandler;
    private static byte[] EMPTY_BYTE = new byte[0];

    public FileEventHandler(int cacheSize, LocalFileAdaptor localFileHandler) {
        super();
        this.eventQueue = new EventQueue<>(cacheSize);
        this.localFileHandler = localFileHandler;
    }

    public FileEventHandler(LocalFileAdaptor localFileHandler) {
        this(30, localFileHandler);
    }

    @Override
    public void process(FileEvent event) {
        if (eventQueue.contains(event)) {
            eventQueue.remove(event);
            System.out.println("skip " + event);
        } else {
            byte code = event.getEventType().getCode();
            System.out.println("deal " + event);
            if (0 == code || 1 == code) {
                File file = localFileHandler.getFile(event.getFilePath());
                if (Constants.DEFULT_BLOCK_SIZE < event.getFileSize()) {
                    try {
                        sendFileBlock(code, event.getFilePath(), 0, -1, FileUtils.readFileToByteArray(file));
                    } catch (IOException e) {
                    }
                } else {
                    if (1 == code) {
                        List<FileBlockChecksum> fileList = new LinkedList<>();
                        long blocks = event.getFileSize() / Constants.DEFULT_BLOCK_SIZE;
                        byte[] data = new byte[Constants.DEFULT_BLOCK_SIZE];
                        for (long i = 0; i <= blocks; i++) {
                            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                                if (i == blocks) {
                                    int lastBlockSize = (int) (event.getFileSize() % Constants.DEFULT_BLOCK_SIZE);
                                    if (0 != lastBlockSize) {
                                        byte[] lastBlock = new byte[lastBlockSize];
                                        raf.read(lastBlock);
                                        fileList.add(new FileBlockChecksum(i, EncodeUtils.md2(lastBlock)));
                                    }
                                } else {
                                    raf.seek(i * Constants.DEFULT_BLOCK_SIZE);
                                    raf.read(data);
                                    fileList.add(new FileBlockChecksum(i, EncodeUtils.md2(data)));
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

    @Override
    public void onMessage(Message message, Session session) throws IOException {
        byte[] payload = message.getPayload();
        if (payload.length > 0) {
            byte header = payload[0];
            int start = 1;
            String filePath = null;
            System.out.println("recive " + header);
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
                        start += 16;
                    }
                    switch (header) {
                    case 0:
                        if (-1 == blockSize) {
                            localFileHandler.createFile(filePath, payload, start);
                        } else {
                            localFileHandler.modifyFile(filePath, index, blockSize, payload, start);
                        }
                        break;
                    case 1:
                        localFileHandler.modifyFile(filePath, index, blockSize, payload, start);
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
        byte[] pathByte = event.getFilePath().getBytes(Constants.DEFAULT_CHARSET);
        byte[] payload = new byte[3 + pathByte.length];
        payload[0] = event.getEventType().getCode();
        System.arraycopy(EncodeUtils.short2Byte((short) pathByte.length), 0, payload, 1, 2);
        System.arraycopy(pathByte, 0, payload, 3, pathByte.length);
        sendToAll(payload);
        System.out.println("send " + event);
    }

    public void sendFileBlockchecksumList(FileEvent event) {
        // TODO
    }

    public void sendFileBlock(byte code, String filePath, long blockIndex, long blockSize, byte[] data) {
        byte[] pathByte = filePath.getBytes(Constants.DEFAULT_CHARSET);
        byte[] payload = new byte[3 + pathByte.length + 16 + data.length];
        payload[0] = code;
        System.arraycopy(EncodeUtils.short2Byte((short) pathByte.length), 0, payload, 1, 2);
        System.arraycopy(pathByte, 0, payload, 3, pathByte.length);
        System.arraycopy(EncodeUtils.long2Byte(blockIndex), 0, payload, 3 + pathByte.length, 8);
        System.arraycopy(EncodeUtils.long2Byte(blockSize), 0, payload, 3 + pathByte.length + 8, 8);
        System.arraycopy(data, 0, payload, 3 + pathByte.length + 16, data.length);
        sendToAll(payload);
        System.out.println("send " + filePath + "\tblock index:" + blockIndex);
    }

    public void sendFileChecksumList(FileEvent event) {
        // TODO
    }

    @Override
    public void cache(FileEvent event) {
        eventQueue.add(event);
    }
}