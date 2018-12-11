package org.microprofile.file.handler;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
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
import org.microprofile.file.constant.Constants;
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

public class RemoteMessageHandler implements MessageHandler {
    protected final Log log = LogFactory.getLog(getClass());
    private Map<String, Session> sessionMap = new HashMap<>();

    private static byte HEADER_FILE_CREATE = EventType.FILE_CREATE.getCode();
    private static byte HEADER_FILE_MODIFY = EventType.FILE_MODIFY.getCode();
    private static byte HEADER_MAX_EVENT = EventType.DIRECTORY_DELETE.getCode();
    private static byte HEADER_FILE_CHECKSUM = 10;
    private static byte HEADER_BLOCK_CHECKSUM = 11;
    private static byte HEADER_FILE_CHECKSUM_RESULT = 12;
    private static byte HEADER_BLOCK_CHECKSUM_RESULT = 13;
    private static byte FILE_HEADER_LENGTH = 3;
    private boolean master = false;

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

    private void handleEvent(byte header, byte[] payload) {
        short filePathLength = EncodeUtils.bype2Short(Arrays.copyOfRange(payload, 1, FILE_HEADER_LENGTH));
        int startIndex = FILE_HEADER_LENGTH + filePathLength;
        if (startIndex <= payload.length) {
            String filePath = new String(Arrays.copyOfRange(payload, FILE_HEADER_LENGTH, startIndex));
            long cacheFileSize = 0;
            long fileSize = 0;
            long blockSize = -1;
            long blockIndex = 0;
            if (0 == header || 1 == header) {
                fileSize = EncodeUtils.bype2Long(Arrays.copyOfRange(payload, startIndex, startIndex + 8));
                blockSize = EncodeUtils.bype2Long(Arrays.copyOfRange(payload, startIndex + 8, startIndex + 16));
                blockIndex = EncodeUtils.bype2Long(Arrays.copyOfRange(payload, startIndex + 16, startIndex + 24));
                startIndex += 24;
            }
            EventType eventType = null;
            switch (header) {
            case 0:
                if (-1 == blockSize) {
                    cacheFileSize = localFileAdaptor.createFile(filePath, payload, startIndex);
                } else {
                    eventType = EventType.FILE_MODIFY;
                    cacheFileSize = localFileAdaptor.modifyFile(filePath, fileSize, blockSize, blockIndex, payload, startIndex);
                }
                break;
            case 1:
                eventType = EventType.FILE_MODIFY;
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
            List<FileChecksum> fileChecksumList = Constants.objectMapper.readValue(payload, 1, payload.length - 1,
                    new TypeReference<List<FileChecksum>>() {
                    });
            if (null != fileChecksumList) {
                List<FileChecksumResult> result = new LinkedList<>();
                for (FileChecksum fileChecksum : fileChecksumList) {
                    String filePath = fileChecksum.getFilePath();
                    File file = localFileAdaptor.getFile(filePath);
                    if (fileChecksum.isDirectory()) {
                        if (!file.exists()) {
                            long fileSize = localFileAdaptor.createDirectory(filePath);
                            eventHandler.cache(new FileEvent(EventType.DIRECTORY_CREATE, filePath, fileSize));
                        }
                    } else {
                        if (file.exists()) {
                            if (0 < fileChecksum.getFileSize()) {
                                if (fileChecksum.getFileSize() != file.length()) {
                                    result.add(new FileChecksumResult(filePath, true));
                                } else {
                                    try {
                                        if (!localFileAdaptor.verifyFile(file, fileChecksum.getChecksum())) {
                                            result.add(new FileChecksumResult(filePath, true));
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
                        } else if (0 == fileChecksum.getFileSize()) {
                            file.createNewFile();
                            eventHandler.cache(new FileEvent(EventType.FILE_CREATE, filePath, 0));
                        } else {
                            result.add(new FileChecksumResult(filePath, false));
                        }
                    }
                }
                if (!result.isEmpty()) {
                    sendFileChecksumResultList(session, result);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleBlockChecksum(byte[] payload, Session session) {
        short filePathLength = EncodeUtils.bype2Short(Arrays.copyOfRange(payload, 1, FILE_HEADER_LENGTH));
        int startIndex = FILE_HEADER_LENGTH + filePathLength;
        if (startIndex + 16 < payload.length) {
            String filePath = new String(Arrays.copyOfRange(payload, FILE_HEADER_LENGTH, startIndex));
            File file = localFileAdaptor.getFile(filePath);
            if (file.exists()) {
                long newFileSize = EncodeUtils.bype2Long(Arrays.copyOfRange(payload, startIndex, startIndex + 8));
                long blockSize = EncodeUtils.bype2Long(Arrays.copyOfRange(payload, startIndex + 8, startIndex + 16));
                startIndex += 16;
                try {
                    List<BlockChecksum> blockChecksumList = Constants.objectMapper.readValue(payload, startIndex,
                            payload.length - startIndex, new TypeReference<List<BlockChecksum>>() {
                            });
                    if (null != blockChecksumList) {
                        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                            long fileSize = file.length();
                            List<Long> result = new LinkedList<>();
                            for (BlockChecksum blockChecksum : blockChecksumList) {
                                long currentIndex = blockSize * blockChecksum.getIndex();
                                if (fileSize <= currentIndex) {
                                    result.add(blockChecksum.getIndex());
                                } else {
                                    int size = (int) (currentIndex + blockSize > fileSize ? fileSize - currentIndex : blockSize);
                                    MappedByteBuffer byteBuffer = raf.getChannel().map(FileChannel.MapMode.READ_ONLY,
                                            blockChecksum.getIndex(), size);
                                    byte[] checksum = EncodeUtils.md2(byteBuffer);
                                    if (!Arrays.equals(checksum, blockChecksum.getChecksum())) {
                                        result.add(blockChecksum.getIndex());
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
                } catch (IOException e) {
                }
            }
        }

    }

    private void handleBlockChecksumResult(byte[] payload, Session session) {
        short filePathLength = EncodeUtils.bype2Short(Arrays.copyOfRange(payload, 1, FILE_HEADER_LENGTH));
        int startIndex = FILE_HEADER_LENGTH + filePathLength;
        if (startIndex + 16 < payload.length) {
            String filePath = new String(Arrays.copyOfRange(payload, FILE_HEADER_LENGTH, startIndex));
            File file = localFileAdaptor.getFile(filePath);
            long oldFileSize = EncodeUtils.bype2Long(Arrays.copyOfRange(payload, startIndex, startIndex + 8));
            if (file.exists() && oldFileSize == file.length()) {
                long blockSize = EncodeUtils.bype2Long(Arrays.copyOfRange(payload, startIndex + 8, startIndex + 16));
                startIndex += 16;
                try {
                    List<Long> blockChecksumResultList = Constants.objectMapper.readValue(payload, startIndex,
                            payload.length - startIndex, new TypeReference<List<Long>>() {
                            });
                    if (null != blockChecksumResultList) {
                        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                            long fileSize = file.length();
                            for (Long blockIndex : blockChecksumResultList) {
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
                    }
                } catch (IOException e) {
                }
            }
        }
    }

    private void handleFileChecksumResult(byte[] payload, Session session) {
        try {
            List<FileChecksumResult> fileChecksumResultList = Constants.objectMapper.readValue(payload, 1, payload.length - 1,
                    new TypeReference<List<FileChecksumResult>>() {
                    });
            if (null != fileChecksumResultList) {
                for (FileChecksumResult fileChecksumResult : fileChecksumResultList) {
                    String filePath = fileChecksumResult.getFilePath();
                    File file = localFileAdaptor.getFile(filePath);
                    if (file.exists()) {
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
                                long blocks = fileSize / blockSize;
                                for (long i = 0; i <= blocks; i++) {
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
                                            MappedByteBuffer byteBuffer = channel.map(FileChannel.MapMode.READ_ONLY,
                                                    i * blockSize, blockSize);
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
                            long blocks = file.length() / blockSize;
                            byte[] data = new byte[blockSize];
                            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                                for (long i = 0; i <= blocks; i++) {
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
            }
        } catch (IOException e) {
        }
    }

    public void handle(byte[] payload, Session session) {
        byte header = payload[0];
        log.info("receive " + header);
        if (0 <= header && HEADER_MAX_EVENT >= header && FILE_HEADER_LENGTH < payload.length) {
            handleEvent(header, payload);
        } else if (HEADER_FILE_CHECKSUM == header && !master) {
            handleFileChecksum(payload, session);
        } else if (HEADER_BLOCK_CHECKSUM == header && FILE_HEADER_LENGTH < payload.length) {
            handleBlockChecksum(payload, session);
        } else if (HEADER_FILE_CHECKSUM_RESULT == header) {
            handleFileChecksumResult(payload, session);
        } else if (HEADER_BLOCK_CHECKSUM_RESULT == header && FILE_HEADER_LENGTH < payload.length) {
            handleBlockChecksumResult(payload, session);
        }
    }

    @Override
    public void onOpen(Session session) throws IOException {
        sessionMap.put(session.getId(), session);
        if (master) {
            new Thread() {
                public void run() {
                    try {
                        Thread.sleep(1000 * 2);
                        sendFileChecksumList(session, localFileAdaptor.getFileChecksumList());
                    } catch (InterruptedException e) {
                    }
                }
            }.start();
        }
    }

    @Override
    public void onClose(Session session) throws IOException {
        sessionMap.remove(session.getId());
    }

    private List<String> send(Session session, byte[] data) {
        List<String> failureList = null;
        if (null == session) {
            for (Session s : sessionMap.values()) {
                try {
                    s.sendByte(data);
                } catch (IOException e) {
                    if (null == failureList) {
                        failureList = new ArrayList<>();
                    }
                    failureList.add(s.getId());
                    log.error("can't send to " + s.getId());
                }
            }
        } else {
            try {
                session.sendByte(data);
            } catch (IOException e) {
                if (null == failureList) {
                    failureList = new ArrayList<>();
                }
                failureList.add(session.getId());
                log.error("can't send to " + session.getId());
            }
        }
        return failureList;
    }

    public void sendEvent(FileEvent event) {
        byte[] pathByte = getFilePathByte(event.getFilePath());
        byte[] payload = new byte[FILE_HEADER_LENGTH + pathByte.length];
        setHeader(event.getEventType().getCode(), pathByte, payload);
        send(null, payload);
        log.info("sent " + event);
    }

    public void sendBlock(Session session, byte code, String filePath, long fileSize, long blockSize, long blockIndex,
            byte[] data) {
        byte[] pathByte = filePath.getBytes(Constants.DEFAULT_CHARSET);
        int headerLength = FILE_HEADER_LENGTH + pathByte.length;
        byte[] payload = new byte[headerLength + 24 + data.length];

        setHeader(code, pathByte, payload);

        System.arraycopy(EncodeUtils.long2Byte(fileSize), 0, payload, headerLength, 8);
        System.arraycopy(EncodeUtils.long2Byte(blockSize), 0, payload, headerLength + 8, 8);
        System.arraycopy(EncodeUtils.long2Byte(blockIndex), 0, payload, headerLength + 16, 8);

        System.arraycopy(data, 0, payload, headerLength + 24, data.length);
        send(session, payload);
        log.info("sent file blocks of:" + filePath + "\t" + data.length);
    }

    public void sendBlockchecksumList(Session session, String filePath, long fileSize, long blockSize,
            List<BlockChecksum> blockList) {
        try {
            sendBlockList(session, HEADER_BLOCK_CHECKSUM, filePath, fileSize, blockSize, blockList);
            log.info("sent file blocks checksum of:" + filePath + "\t" + blockList.size());
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
    }

    private void sendBlockChecksumResultList(Session session, String filePath, long fileSize, long blockSize,
            List<Long> blockList) {
        try {
            sendBlockList(session, HEADER_BLOCK_CHECKSUM_RESULT, filePath, fileSize, blockSize, blockList);
            log.info("sent file blocks checksum result of:" + filePath + "\t" + blockList.size());
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
    }

    private void sendBlockList(Session session, byte code, String filePath, long fileSize, long blockSize, List<?> blockList)
            throws JsonProcessingException {
        byte[] pathByte = getFilePathByte(filePath);
        int headerLength = FILE_HEADER_LENGTH + pathByte.length;
        byte[] data = Constants.objectMapper.writeValueAsBytes(blockList);
        byte[] payload = new byte[headerLength + 16 + data.length];

        setHeader(code, pathByte, payload);

        System.arraycopy(EncodeUtils.long2Byte(blockSize), 0, payload, headerLength, 8);
        System.arraycopy(EncodeUtils.long2Byte(fileSize), 0, payload, headerLength + 8, 8);
        System.arraycopy(data, 0, payload, headerLength + 16, data.length);
        send(session, payload);
    }

    private void sendFileChecksumList(Session session, List<FileChecksum> fileList) {
        try {
            sendFileList(session, HEADER_FILE_CHECKSUM, fileList);
            log.info("sent file checksum list:" + fileList.size());
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
    }

    private void sendFileChecksumResultList(Session session, List<FileChecksumResult> fileList) {
        try {
            sendFileList(session, HEADER_FILE_CHECKSUM_RESULT, fileList);
            log.info("sent file checksum result list:" + fileList.size());
        } catch (JsonProcessingException e) {
        }
    }

    private void sendFileList(Session session, byte code, List<?> fileList) throws JsonProcessingException {
        byte[] data = Constants.objectMapper.writeValueAsBytes(fileList);
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
        System.arraycopy(EncodeUtils.short2Byte((short) pathByte.length), 0, payload, 1, 2);
        System.arraycopy(pathByte, 0, payload, FILE_HEADER_LENGTH, pathByte.length);
    }
}
