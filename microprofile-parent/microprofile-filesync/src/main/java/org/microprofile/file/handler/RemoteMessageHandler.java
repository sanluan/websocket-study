package org.microprofile.file.handler;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.microprofile.common.constant.Constants;
import org.microprofile.common.utils.EncodeUtils;
import org.microprofile.file.event.EventHandler;
import org.microprofile.file.event.FileEvent;
import org.microprofile.file.event.FileEvent.EventType;
import org.microprofile.file.message.BlockChecksum;
import org.microprofile.file.message.FileChecksum;
import org.microprofile.file.message.FileChecksumResult;
import org.microprofile.websocket.handler.MessageHandler;
import org.microprofile.websocket.handler.Session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public class RemoteMessageHandler implements MessageHandler {
    protected final Log log = LogFactory.getLog(getClass());
    private Map<String, Session> sessionMap = new HashMap<>();
    /**
     * Json Mapper
     */
    public static final ObjectMapper objectMapper = new ObjectMapper();

    private static byte HEADER_FILE_CREATE = EventType.FILE_CREATE.getCode();
    private static byte HEADER_FILE_MODIFY = EventType.FILE_MODIFY.getCode();
    private static byte HEADER_MAX_EVENT = EventType.DIRECTORY_DELETE.getCode();
    private static byte HEADER_FILE_CHECKSUM = 10;
    private static byte HEADER_BLOCK_CHECKSUM = 11;
    private static byte HEADER_FILE_CHECKSUM_RESULT = 12;
    private static byte HEADER_BLOCK_CHECKSUM_RESULT = 13;
    private static byte HEADER_LENGTH = 3;

    private static int LENGTH_SHORT = 2;
    private static int LENGTH_INT = 4;
    private static int LENGTH_LONG = 8;
    private boolean master = false;

    private EventHandler eventHandler;
    private LocalFileAdaptor localFileAdaptor;

    /**
     * @return the sessionMap
     */
    public boolean hasSession() {
        return !sessionMap.isEmpty();
    }

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

    private void handleEvent(byte header, byte[] payload) {
        short filePathLength = EncodeUtils.bype2Short(Arrays.copyOfRange(payload, 1, HEADER_LENGTH));
        int startIndex = HEADER_LENGTH + filePathLength;
        if (startIndex <= payload.length) {
            String filePath = new String(Arrays.copyOfRange(payload, HEADER_LENGTH, startIndex));
            long cacheFileSize = 0;
            long fileSize = 0;
            int blockSize = -1;
            int blockIndex = 0;
            if (0 == header || 1 == header) {
                fileSize = EncodeUtils.bype2Long(Arrays.copyOfRange(payload, startIndex, startIndex + LENGTH_LONG));
                startIndex += LENGTH_LONG;
                blockSize = EncodeUtils.bype2Int(Arrays.copyOfRange(payload, startIndex, startIndex + LENGTH_INT));
                startIndex += LENGTH_INT;
                blockIndex = EncodeUtils.bype2Int(Arrays.copyOfRange(payload, startIndex, startIndex + LENGTH_INT));
                startIndex += LENGTH_INT;
            }
            EventType eventType = null;
            switch (header) {
            case 0:
                if (-1 == blockSize) {
                    cacheFileSize = localFileAdaptor.createFile(filePath, payload, startIndex);
                } else {
                    cacheFileSize = localFileAdaptor.modifyFile(filePath, fileSize, blockSize, blockIndex, payload, startIndex);
                }
                if (localFileAdaptor.getFile(filePath).exists()) {
                    eventType = EventType.FILE_MODIFY;
                } else {
                    eventType = EventType.FILE_CREATE;
                }
                break;
            case 1:
                if (localFileAdaptor.getFile(filePath).exists()) {
                    eventType = EventType.FILE_MODIFY;
                } else {
                    eventType = EventType.FILE_CREATE;
                }
                cacheFileSize = localFileAdaptor.modifyFile(filePath, fileSize, blockSize, blockIndex, payload, startIndex);
                break;
            case 2:
                eventType = EventType.FILE_DELETE;
                cacheFileSize = localFileAdaptor.deleteFile(filePath);
                break;
            case 3:
                eventType = EventType.DIRECTORY_CREATE;
                cacheFileSize = localFileAdaptor.createDirectory(filePath);
                break;
            case 4:
                eventType = EventType.DIRECTORY_DELETE;
                cacheFileSize = localFileAdaptor.deleteDirectory(filePath);
                break;
            }
            if (null != eventType) {
                eventHandler.cache(new FileEvent(eventType, filePath, cacheFileSize));
            }
        }
    }

    private void handleFileChecksum(byte[] payload, Session session) {
        try {
            FileChecksum fileChecksum = objectMapper.readValue(payload, 1, payload.length - 1, FileChecksum.class);
            if (null != fileChecksum) {
                FileChecksumResult result = null;
                String filePath = fileChecksum.getFilePath();
                File file = localFileAdaptor.getFile(filePath);
                if (fileChecksum.isDirectory()) {
                    if (!file.exists()) {
                        long fileSize = localFileAdaptor.createDirectory(filePath);
                        eventHandler.cache(new FileEvent(EventType.DIRECTORY_CREATE, filePath, fileSize));
                    }
                } else {
                    if (file.exists() && !file.isDirectory()) {
                        if (0 < fileChecksum.getFileSize()) {
                            if (fileChecksum.getFileSize() != file.length()) {
                                result = new FileChecksumResult(filePath, true);
                            } else {
                                try {
                                    if (!localFileAdaptor.verifyFile(file, fileChecksum.getChecksum())) {
                                        result = new FileChecksumResult(filePath, true);
                                    }
                                } catch (FileNotFoundException e) {
                                } catch (IOException e) {
                                }
                            }
                        } else if (0 == fileChecksum.getFileSize() && 0 < file.length()) {
                            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                                raf.setLength(0);
                            }
                        }
                    } else if (!file.exists() && 0 == fileChecksum.getFileSize()) {
                        file.createNewFile();
                        eventHandler.cache(new FileEvent(EventType.FILE_CREATE, filePath, 0));
                    } else {
                        result = new FileChecksumResult(filePath, false);
                    }
                }
                if (null != result) {
                    sendFileChecksumResult(session, result);
                } else {
                    log.info(fileChecksum.getFilePath() + " are synchronized!");
                }
            }
        } catch (

        IOException e) {
            e.printStackTrace();
        }
    }

    private void handleBlockChecksum(byte[] payload, Session session) {
        short filePathLength = EncodeUtils.bype2Short(Arrays.copyOfRange(payload, 1, HEADER_LENGTH));
        int startIndex = HEADER_LENGTH + filePathLength;
        if (startIndex + LENGTH_LONG + LENGTH_INT < payload.length) {
            String filePath = new String(Arrays.copyOfRange(payload, HEADER_LENGTH, startIndex));
            File file = localFileAdaptor.getFile(filePath);
            if (file.exists()) {
                long newFileSize = EncodeUtils.bype2Long(Arrays.copyOfRange(payload, startIndex, startIndex + LENGTH_LONG));
                startIndex += LENGTH_LONG;
                int blockSize = EncodeUtils.bype2Int(Arrays.copyOfRange(payload, startIndex, startIndex + LENGTH_INT));
                startIndex += LENGTH_INT;
                try {
                    List<BlockChecksum> blockChecksumList = objectMapper.readValue(payload, startIndex,
                            payload.length - startIndex, new TypeReference<List<BlockChecksum>>() {
                            });
                    if (null != blockChecksumList) {
                        List<Integer> result = new LinkedList<>();
                        if (file.isDirectory()) {
                            for (BlockChecksum blockChecksum : blockChecksumList) {
                                result.add(blockChecksum.getBlockIndex());
                            }
                            if (!result.isEmpty()) {
                                sendBlockChecksumResultList(session, filePath, newFileSize, blockSize, result);
                            }
                        } else {
                            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                                long fileSize = file.length();
                                for (BlockChecksum blockChecksum : blockChecksumList) {
                                    long currentIndex = blockSize * blockChecksum.getBlockIndex();
                                    if (fileSize <= currentIndex) {
                                        result.add(blockChecksum.getBlockIndex());
                                    } else {
                                        int size = (int) (currentIndex + blockSize > fileSize ? fileSize - currentIndex
                                                : blockSize);
                                        MappedByteBuffer byteBuffer = raf.getChannel().map(FileChannel.MapMode.READ_ONLY,
                                                currentIndex, size);
                                        byte[] checksum = EncodeUtils.md2(byteBuffer);
                                        if (!Arrays.equals(checksum, blockChecksum.getChecksum())) {
                                            result.add(blockChecksum.getBlockIndex());
                                        }
                                    }
                                }
                                if (!result.isEmpty()) {
                                    sendBlockChecksumResultList(session, filePath, newFileSize, blockSize, result);
                                }
                            } catch (FileNotFoundException e) {
                                e.printStackTrace();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }
                } catch (IOException e) {
                }
            }
        }

    }

    private void handleBlockChecksumResult(byte[] payload, Session session) {
        short filePathLength = EncodeUtils.bype2Short(Arrays.copyOfRange(payload, 1, HEADER_LENGTH));
        int startIndex = HEADER_LENGTH + filePathLength;
        if (startIndex + LENGTH_LONG + LENGTH_INT < payload.length) {
            String filePath = new String(Arrays.copyOfRange(payload, HEADER_LENGTH, startIndex));
            File file = localFileAdaptor.getFile(filePath);
            long oldFileSize = EncodeUtils.bype2Long(Arrays.copyOfRange(payload, startIndex, startIndex + LENGTH_LONG));
            if (file.exists() && !file.isDirectory() && oldFileSize == file.length()) {
                startIndex += LENGTH_LONG;
                int blockSize = EncodeUtils.bype2Int(Arrays.copyOfRange(payload, startIndex, startIndex + LENGTH_INT));
                startIndex += LENGTH_INT;
                try {
                    List<Integer> blockChecksumResultList = objectMapper.readValue(payload, startIndex,
                            payload.length - startIndex, new TypeReference<List<Integer>>() {
                            });
                    if (null != blockChecksumResultList) {
                        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                            long fileSize = file.length();
                            for (Integer blockIndex : blockChecksumResultList) {
                                long currentIndex = blockSize * blockIndex;
                                int size = (int) (currentIndex + blockSize > fileSize ? fileSize - currentIndex : blockSize);
                                byte[] data = new byte[size];
                                raf.seek(currentIndex);
                                raf.read(data);
                                sendBlock(session, HEADER_FILE_MODIFY, filePath, fileSize, blockSize, blockIndex, data);
                            }
                        } catch (FileNotFoundException e) {
                            e.printStackTrace();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    } else {
                        log.info(filePath + " all blocks are synchronized!");
                    }
                } catch (IOException e) {
                }
            }
        }
    }

    private void handleFileChecksumResult(byte[] payload, Session session) {
        try {
            FileChecksumResult fileChecksumResult = objectMapper.readValue(payload, 1, payload.length - 1,
                    FileChecksumResult.class);
            if (null != fileChecksumResult) {
                String filePath = fileChecksumResult.getFilePath();
                File file = localFileAdaptor.getFile(filePath);
                if (file.exists() && !file.isDirectory()) {
                    int blockSize = localFileAdaptor.getBlockSize();
                    long fileSize = file.length();
                    if (fileChecksumResult.isExists()) {
                        if (blockSize > fileSize) {
                            try {
                                sendBlock(session, HEADER_FILE_MODIFY, filePath, fileSize, -1, 0,
                                        FileUtils.readFileToByteArray(file));
                            } catch (IOException e) {
                            }
                        } else {
                            List<BlockChecksum> blockChecksumList = new LinkedList<>();
                            int blocks = (int) (fileSize / blockSize);
                            for (int i = 0; i <= blocks; i++) {
                                try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                                    FileChannel channel = raf.getChannel();
                                    if (i == blocks) {
                                        int lastBlockSize = (int) (fileSize % blockSize);
                                        if (0 != lastBlockSize) {
                                            MappedByteBuffer byteBuffer = channel.map(FileChannel.MapMode.READ_ONLY,
                                                    i * blockSize, lastBlockSize);
                                            blockChecksumList.add(new BlockChecksum(i, EncodeUtils.md2(byteBuffer)));
                                        }
                                    } else {
                                        MappedByteBuffer byteBuffer = channel.map(FileChannel.MapMode.READ_ONLY, i * blockSize,
                                                blockSize);
                                        blockChecksumList.add(new BlockChecksum(i, EncodeUtils.md2(byteBuffer)));
                                    }
                                } catch (FileNotFoundException e) {
                                    blockChecksumList.clear();
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                            if (!blockChecksumList.isEmpty()) {
                                sendBlockchecksumList(session, filePath, fileSize, blockSize, blockChecksumList);
                            }
                        }
                    } else {
                        int blocks = (int) (file.length() / blockSize);
                        byte[] data = new byte[blockSize];
                        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                            for (int i = 0; i <= blocks; i++) {
                                if (i == blocks) {
                                    int lastBlockSize = (int) (file.length() % blockSize);
                                    if (0 != lastBlockSize) {
                                        byte[] lastBlock = new byte[lastBlockSize];
                                        raf.read(lastBlock);
                                        sendBlock(session, HEADER_FILE_CREATE, filePath, fileSize, blockSize, i, lastBlock);
                                    }
                                } else {
                                    raf.seek(i * blockSize);
                                    raf.read(data);
                                    sendBlock(session, HEADER_FILE_CREATE, filePath, fileSize, blockSize, i, data);
                                }
                            }
                        } catch (FileNotFoundException e) {
                            e.printStackTrace();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        } catch (IOException e) {
        }
    }

    public void handle(byte[] payload, Session session) {
        byte header = payload[0];
        if (0 <= header && HEADER_MAX_EVENT >= header && HEADER_LENGTH < payload.length) {
            log.info("文件消息\t" + header);
            handleEvent(header, payload);
        } else if (HEADER_FILE_CHECKSUM == header && !master) {
            log.info("文件校验消息\t" + payload.length);
            handleFileChecksum(payload, session);
        } else if (HEADER_BLOCK_CHECKSUM == header && HEADER_LENGTH < payload.length) {
            log.info("文件块校验消息\t" + payload.length);
            handleBlockChecksum(payload, session);
        } else if (HEADER_FILE_CHECKSUM_RESULT == header) {
            log.info("文件校验结果\t" + header);
            handleFileChecksumResult(payload, session);
        } else if (HEADER_BLOCK_CHECKSUM_RESULT == header && HEADER_LENGTH < payload.length) {
            log.info("文件块校验结果\t" + header);
            handleBlockChecksumResult(payload, session);
        }
    }

    @Override
    public void onOpen(Session session) throws IOException {
        sessionMap.put(session.getId(), session);
        log.info(session.getId() + "\t connected!");
        RemoteMessageHandler remoteMessageHandler = this;
        if (master) {
            StringBuilder sb = new StringBuilder("Thread [Client ");
            sb.append(session.getId()).append(" file sync task]");
            new Thread() {
                public void run() {
                    try {
                        Thread.sleep(1000 * 2);
                        localFileAdaptor.fileChecksum(session, remoteMessageHandler);
                    } catch (InterruptedException e) {
                    }
                }
            }.start();
        }
    }

    @Override
    public void onClose(Session session) throws IOException {
        sessionMap.remove(session.getId());
        log.info(session.getId() + "\t closed!");
    }

    private void send(Session session, byte[] data) {
        if (null == session) {
            for (Session s : sessionMap.values()) {
                try {
                    s.sendByte(data);
                } catch (IOException e) {
                    try {
                        s.close();
                    } catch (IOException e1) {
                    }
                    log.error("can't send to " + s.getId());
                }
            }
        } else {
            try {
                session.sendByte(data);
            } catch (IOException e) {
                try {
                    session.close();
                } catch (IOException e1) {
                }
                log.error("can't send to " + session.getId());
            }
        }
    }

    public void sendEvent(FileEvent event) {
        byte[] pathByte = getFilePathByte(event.getFilePath());
        byte[] payload = new byte[HEADER_LENGTH + pathByte.length];
        setHeader(event.getEventType().getCode(), pathByte, payload);
        send(null, payload);
        log.info("sent " + event);
    }

    public void sendBlock(Session session, byte code, String filePath, long fileSize, int blockSize, int blockIndex,
            byte[] data) {
        byte[] pathByte = filePath.getBytes(Constants.DEFAULT_CHARSET);
        int headerLength = HEADER_LENGTH + pathByte.length;
        byte[] payload = new byte[headerLength + LENGTH_LONG + LENGTH_INT + LENGTH_INT + data.length];

        setHeader(code, pathByte, payload);

        System.arraycopy(EncodeUtils.long2Byte(fileSize), 0, payload, headerLength, LENGTH_LONG);
        headerLength += LENGTH_LONG;
        System.arraycopy(EncodeUtils.int2Byte(blockSize), 0, payload, headerLength, LENGTH_INT);
        headerLength += LENGTH_INT;
        System.arraycopy(EncodeUtils.int2Byte(blockIndex), 0, payload, headerLength, LENGTH_INT);
        headerLength += LENGTH_INT;
        System.arraycopy(data, 0, payload, headerLength, data.length);
        send(session, payload);
        log.info("sent file blocks of:" + filePath + "\t" + data.length);
    }

    public void sendBlockchecksumList(Session session, String filePath, long fileSize, int blockSize,
            List<BlockChecksum> blockList) {
        try {
            sendBlockList(session, HEADER_BLOCK_CHECKSUM, filePath, fileSize, blockSize, blockList);
            log.info("sent file blocks checksum of:" + filePath + "\t" + blockList.size());
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
    }

    private void sendBlockChecksumResultList(Session session, String filePath, long fileSize, int blockSize,
            List<Integer> blockList) {
        try {
            sendBlockList(session, HEADER_BLOCK_CHECKSUM_RESULT, filePath, fileSize, blockSize, blockList);
            log.info("sent file blocks checksum result of:" + filePath + "\t" + blockList.size());
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
    }

    private void sendBlockList(Session session, byte code, String filePath, long fileSize, int blockSize, List<?> blockList)
            throws JsonProcessingException {
        byte[] pathByte = getFilePathByte(filePath);
        int headerLength = HEADER_LENGTH + pathByte.length;
        byte[] data = objectMapper.writeValueAsBytes(blockList);
        byte[] payload = new byte[headerLength + LENGTH_LONG + LENGTH_INT + data.length];

        setHeader(code, pathByte, payload);
        System.arraycopy(EncodeUtils.long2Byte(fileSize), 0, payload, headerLength, LENGTH_LONG);
        headerLength += LENGTH_LONG;
        System.arraycopy(EncodeUtils.int2Byte(blockSize), 0, payload, headerLength, LENGTH_INT);
        headerLength += LENGTH_INT;
        System.arraycopy(data, 0, payload, headerLength, data.length);
        send(session, payload);
    }

    public void sendFileChecksum(Session session, FileChecksum checkSum) {
        try {
            sendFile(session, HEADER_FILE_CHECKSUM, checkSum);
            log.info("sent file checksum list:" + checkSum.getFilePath());
        } catch (JsonProcessingException e) {
        }
    }

    public void sendFileChecksumResult(Session session, FileChecksumResult result) {
        try {
            sendFile(session, HEADER_FILE_CHECKSUM_RESULT, result);
            log.info("sent file checksum result:" + result.getFilePath());
        } catch (JsonProcessingException e) {
        }
    }

    private void sendFile(Session session, byte code, Object object) throws JsonProcessingException {
        byte[] data = objectMapper.writeValueAsBytes(object);
        byte[] payload = new byte[1 + data.length];
        payload[0] = code;
        System.arraycopy(data, 0, payload, 1, data.length);
        try {
            session.sendByte(payload);
        } catch (IOException e) {
            log.error("can't send to " + session.getId());
        }
    }

    private byte[] getFilePathByte(String filePath) {
        return filePath.getBytes(Constants.DEFAULT_CHARSET);
    }

    /**
     * @return the master
     */
    public boolean isMaster() {
        return master;
    }

    /**
     * @param master
     *            the master to set
     */
    public void setMaster(boolean master) {
        this.master = master;
    }

    private void setHeader(byte code, byte[] pathByte, byte[] payload) {
        payload[0] = code;
        System.arraycopy(EncodeUtils.short2Byte((short) pathByte.length), 0, payload, 1, LENGTH_SHORT);
        System.arraycopy(pathByte, 0, payload, HEADER_LENGTH, pathByte.length);
    }
}
