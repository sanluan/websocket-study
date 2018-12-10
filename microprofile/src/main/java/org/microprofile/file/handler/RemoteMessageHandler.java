package org.microprofile.file.handler;

import java.io.File;
import java.io.FileInputStream;
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
        EventType eventType = null;
        if (startIndex <= payload.length) {
            String filePath = new String(Arrays.copyOfRange(payload, FILE_HEADER_LENGTH, startIndex));
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
    }

    private void handleFileChecksum(byte[] payload, Session session) {
        try {
            List<FileChecksum> fileChecksumList = Constants.objectMapper.readValue(payload, 1, payload.length,
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
                                    try (FileInputStream fin = new FileInputStream(file)) {
                                        MappedByteBuffer byteBuffer = fin.getChannel().map(FileChannel.MapMode.READ_ONLY, 0,
                                                file.length());
                                        byte[] checksum = EncodeUtils.md2(byteBuffer);
                                        if (!Arrays.equals(checksum, fileChecksum.getChecksum())) {
                                            result.add(new FileChecksumResult(filePath, true));
                                        }
                                    } catch (FileNotFoundException e) {
                                    } catch (Exception e) {
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
                sendFileChecksumResultList(session, result);
            }
        } catch (IOException e) {
        }
    }

    private void handleBlockChecksum(byte[] payload, Session session) {
        short filePathLength = EncodeUtils.bype2Short(Arrays.copyOfRange(payload, 1, FILE_HEADER_LENGTH));
        int startIndex = FILE_HEADER_LENGTH + filePathLength;
        if (startIndex + 16 < payload.length) {
            String filePath = new String(Arrays.copyOfRange(payload, FILE_HEADER_LENGTH, startIndex));
            File file = localFileAdaptor.getFile(filePath);
            if (file.exists()) {
                long fileSize = EncodeUtils.bype2Long(Arrays.copyOfRange(payload, startIndex, startIndex + 8));
                long blockSize = EncodeUtils.bype2Long(Arrays.copyOfRange(payload, startIndex + 8, startIndex + 16));
                startIndex += 16;
                try {
                    List<BlockChecksum> blockChecksumList = Constants.objectMapper.readValue(payload, startIndex, payload.length,
                            new TypeReference<List<BlockChecksum>>() {
                            });
                    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                        long fileLength = file.length();
                        if (fileSize >= fileLength) {
                            List<Long> result = new LinkedList<>();
                            for (BlockChecksum blockChecksum : blockChecksumList) {
                                long currentIndex = blockSize * blockChecksum.getIndex();
                                if (fileLength <= currentIndex) {
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
                                sendBlockChecksumResultList(session, filePath, fileLength, blockSize, result);
                            }
                        } else {
                            raf.setLength(fileSize);
                            for (BlockChecksum blockChecksum : blockChecksumList) {
                                long currentIndex = blockSize * blockChecksum.getIndex();
                                int size = (int) (currentIndex + blockSize > fileSize ? fileSize - currentIndex : blockSize);
                                MappedByteBuffer byteBuffer = raf.getChannel().map(FileChannel.MapMode.READ_ONLY,
                                        blockChecksum.getIndex(), size);
                                byte[] checksum = EncodeUtils.md2(byteBuffer);
                                if (!Arrays.equals(checksum, blockChecksum.getChecksum())) {
                                    byte[] data = new byte[size];
                                    raf.seek(currentIndex);
                                    raf.read(data);
                                    sendFileBlock(session, HEADER_FILE_MODIFY, filePath, blockChecksum.getIndex(), blockSize,
                                            data);
                                }
                            }
                        }
                    } catch (FileNotFoundException e) {
                    } catch (Exception e) {
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
            if (file.exists()) {
                long fileSize = EncodeUtils.bype2Long(Arrays.copyOfRange(payload, startIndex, startIndex + 8));
                long blockSize = EncodeUtils.bype2Long(Arrays.copyOfRange(payload, startIndex + 8, startIndex + 16));
                startIndex += 16;
                try {
                    List<BlockChecksum> blockChecksumList = Constants.objectMapper.readValue(payload, startIndex, payload.length,
                            new TypeReference<List<BlockChecksum>>() {
                            });
                    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                        long fileLength = file.length();
                        if (fileSize >= fileLength) {
                            for (BlockChecksum blockChecksum : blockChecksumList) {
                                long currentIndex = blockSize * blockChecksum.getIndex();
                                int size = (int) (currentIndex + blockSize > fileSize ? fileSize - currentIndex : blockSize);
                                MappedByteBuffer byteBuffer = raf.getChannel().map(FileChannel.MapMode.READ_ONLY,
                                        blockChecksum.getIndex(), size);
                                byte[] checksum = EncodeUtils.md2(byteBuffer);
                                if (!Arrays.equals(checksum, blockChecksum.getChecksum())) {
                                    byte[] data = new byte[size];
                                    raf.seek(currentIndex);
                                    raf.read(data);
                                    sendFileBlock(session, HEADER_FILE_MODIFY, filePath, blockChecksum.getIndex(), blockSize,
                                            data);
                                }
                            }
                        } else {
                            raf.setLength(fileSize);
                            for (BlockChecksum blockChecksum : blockChecksumList) {
                                long currentIndex = blockSize * blockChecksum.getIndex();
                                int size = (int) (currentIndex + blockSize > fileSize ? fileSize - currentIndex : blockSize);
                                MappedByteBuffer byteBuffer = raf.getChannel().map(FileChannel.MapMode.READ_ONLY,
                                        blockChecksum.getIndex(), size);
                                byte[] checksum = EncodeUtils.md2(byteBuffer);
                                if (!Arrays.equals(checksum, blockChecksum.getChecksum())) {
                                    byte[] data = new byte[size];
                                    raf.seek(currentIndex);
                                    raf.read(data);
                                    sendFileBlock(session, HEADER_FILE_MODIFY, filePath, blockChecksum.getIndex(), blockSize,
                                            data);
                                }
                            }
                        }
                    } catch (FileNotFoundException e) {
                    } catch (Exception e) {
                    }
                } catch (IOException e) {
                }
            }
        }
    }

    private void handleFileChecksumResult(byte[] payload, Session session) {
        try {
            List<FileChecksumResult> fileChecksumResultList = Constants.objectMapper.readValue(payload, 1, payload.length,
                    new TypeReference<List<FileChecksumResult>>() {
                    });
            if (null != fileChecksumResultList) {
                for (FileChecksumResult fileChecksumResult : fileChecksumResultList) {
                    String filePath = fileChecksumResult.getFilePath();
                    File file = localFileAdaptor.getFile(filePath);
                    if (file.exists()) {
                        int blockSize = localFileAdaptor.getBlockSize();
                        if (fileChecksumResult.isExists()) {
                            List<BlockChecksum> blockChecksumList = new LinkedList<>();
                            long fileLength = file.length();
                            long blocks = fileLength / blockSize;
                            for (long i = 0; i <= blocks; i++) {
                                try (RandomAccessFile raf = new RandomAccessFile(file, "r");
                                        FileChannel channel = raf.getChannel()) {
                                    if (i == blocks) {
                                        int lastBlockSize = (int) (fileLength % blockSize);
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
                                }
                            }
                            if (!blockChecksumList.isEmpty()) {
                                sendBlockchecksumList(session, filePath, fileLength, blockSize, blockChecksumList);
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
                                            sendFileBlock(session, HEADER_FILE_CREATE, filePath, i, blockSize, lastBlock);
                                        }
                                    } else {
                                        raf.seek(i * blockSize);
                                        raf.read(data);
                                        sendFileBlock(session, HEADER_FILE_CREATE, filePath, i, blockSize, data);
                                    }
                                }
                            } catch (FileNotFoundException e) {
                            } catch (IOException e) {
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
        } else if (HEADER_FILE_CHECKSUM == header) {
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
        new Thread() {
            public void run() {
                try {
                    Thread.sleep(1000 * 10);
                    sendFileChecksumList(session, localFileAdaptor.getFileChecksumList());
                } catch (InterruptedException e) {
                }
            }
        }.start();
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

    public void sendFileBlock(Session session, byte code, String filePath, long blockIndex, long blockSize, byte[] data) {
        byte[] pathByte = filePath.getBytes(Constants.DEFAULT_CHARSET);
        int headerLength = FILE_HEADER_LENGTH + pathByte.length;
        byte[] payload = new byte[headerLength + 16 + data.length];

        setHeader(code, pathByte, payload);

        System.arraycopy(EncodeUtils.long2Byte(blockIndex), 0, payload, headerLength, 8);
        System.arraycopy(EncodeUtils.long2Byte(blockSize), 0, payload, headerLength + 8, 8);

        System.arraycopy(data, 0, payload, headerLength + 16, data.length);
        send(session, payload);
        log.info("sent file blocks of:" + filePath);
    }

    public void sendBlockchecksumList(Session session, String filePath, long fileSize, long blockSize,
            List<BlockChecksum> blockList) {
        try {
            sendBlockList(session, HEADER_BLOCK_CHECKSUM, filePath, fileSize, blockSize, blockList);
            log.info("sent file blocks checksum of:" + filePath);
        } catch (JsonProcessingException e) {
        }
    }

    private void sendBlockChecksumResultList(Session session, String filePath, long fileSize, long blockSize,
            List<Long> blockList) {
        try {
            sendBlockList(session, HEADER_BLOCK_CHECKSUM_RESULT, filePath, fileSize, blockSize, blockList);
            log.info("sent file blocks checksum result of:" + filePath);
        } catch (JsonProcessingException e) {
        }
    }

    private void sendBlockList(Session session, byte code, String filePath, long fileSize, long blockSize, List<?> blockList)
            throws JsonProcessingException {
        byte[] pathByte = getFilePathByte(filePath);
        int headerLength = FILE_HEADER_LENGTH + pathByte.length;
        byte[] data = Constants.objectMapper.writeValueAsBytes(blockList);
        byte[] payload = new byte[headerLength + data.length];

        setHeader(code, pathByte, payload);

        System.arraycopy(EncodeUtils.long2Byte(blockSize), 0, payload, headerLength, 8);
        System.arraycopy(EncodeUtils.long2Byte(fileSize), 0, payload, headerLength + 8, 8);
        System.arraycopy(data, 0, payload, headerLength + 16, data.length);
        send(session, payload);
    }

    private void sendFileChecksumList(Session session, List<FileChecksum> fileList) {
        try {
            sendFileList(session, HEADER_FILE_CHECKSUM, fileList);
            log.info("sent file checksum list:" + fileList);
        } catch (JsonProcessingException e) {
        }
    }

    private void sendFileChecksumResultList(Session session, List<FileChecksumResult> fileList) {
        try {
            sendFileList(session, HEADER_FILE_CHECKSUM_RESULT, fileList);
            log.info("sent file checksum list:" + fileList);
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

    private void setHeader(byte code, byte[] pathByte, byte[] payload) {
        payload[0] = code;
        System.arraycopy(EncodeUtils.short2Byte((short) pathByte.length), 0, payload, 1, 2);
        System.arraycopy(pathByte, 0, payload, FILE_HEADER_LENGTH, pathByte.length);
    }
}
