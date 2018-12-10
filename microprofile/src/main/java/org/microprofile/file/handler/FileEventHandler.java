package org.microprofile.file.handler;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.microprofile.common.utils.EncodeUtils;
import org.microprofile.common.utils.EventQueue;
import org.microprofile.file.event.EventHandler;
import org.microprofile.file.event.FileEvent;
import org.microprofile.file.event.FileEvent.EventType;
import org.microprofile.file.message.BlockChecksumMessage.BlockChecksum;

public class FileEventHandler implements EventHandler {
    protected final Log log = LogFactory.getLog(getClass());

    /**
     */
    public final static int DEFULT_BLOCK_SIZE = 1024 * 10;

    public final static int DEFULT_CACHE_SIZE = 30;
    private static byte[] EMPTY_BYTE = new byte[0];
    private static byte HEADER_FILE_CREATE = EventType.FILE_CREATE.getCode();
    private static byte HEADER_FILE_MODIFY = EventType.FILE_MODIFY.getCode();

    private int blockSize;
    private EventQueue<FileEvent> eventQueue;
    private LocalFileAdaptor localFileAdaptor;
    private RemoteMessageHandler remoteMessageHandler;

    /**
     * @param localFileHandler
     */
    public FileEventHandler(LocalFileAdaptor localFileHandler) {
        this(localFileHandler, DEFULT_BLOCK_SIZE, DEFULT_CACHE_SIZE);
    }

    /**
     * @param blockSize
     * @param localFileHandler
     */
    public FileEventHandler(LocalFileAdaptor localFileHandler, int blockSize) {
        this(localFileHandler, blockSize, DEFULT_CACHE_SIZE);
    }

    /**
     * @param cacheSize
     * @param blockSize
     * @param localFileAdaptor
     */
    public FileEventHandler(LocalFileAdaptor localFileAdaptor, int blockSize, int cacheSize) {
        super();
        this.blockSize = blockSize;
        this.eventQueue = new EventQueue<>(cacheSize);
        this.localFileAdaptor = localFileAdaptor;
        this.remoteMessageHandler = new RemoteMessageHandler(this, localFileAdaptor);
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
                if (blockSize < event.getFileSize()) {
                    try {
                        remoteMessageHandler.sendFileBlock(code, event.getFilePath(), 0, -1, FileUtils.readFileToByteArray(file));
                    } catch (IOException e) {
                    }
                } else {
                    if (HEADER_FILE_MODIFY == code) {
                        List<BlockChecksum> blockChecksumList = new LinkedList<>();
                        long blocks = event.getFileSize() / blockSize;
                        for (long i = 0; i <= blocks; i++) {
                            try (RandomAccessFile raf = new RandomAccessFile(file, "r"); FileChannel channel = raf.getChannel()) {
                                if (i == blocks) {
                                    int lastBlockSize = (int) (event.getFileSize() % blockSize);
                                    if (0 != lastBlockSize) {
                                        MappedByteBuffer byteBuffer = channel.map(FileChannel.MapMode.READ_ONLY, i * blockSize,
                                                lastBlockSize);
                                        blockChecksumList.add(new BlockChecksum(i, EncodeUtils.md2(byteBuffer)));
                                    }
                                } else {
                                    MappedByteBuffer byteBuffer = channel.map(FileChannel.MapMode.READ_ONLY, i * blockSize,
                                            blockSize);
                                    blockChecksumList.add(new BlockChecksum(i, EncodeUtils.md2(byteBuffer)));
                                }
                            } catch (FileNotFoundException e) {
                                event.setEventType(EventType.FILE_DELETE);
                                remoteMessageHandler.sendEvent(event);
                                blockChecksumList.clear();
                            } catch (IOException e) {
                            }
                        }
                        if (!blockChecksumList.isEmpty()) {
                            remoteMessageHandler.sendBlockchecksumList(event.getFilePath(), blockSize, blockChecksumList);
                        }
                    } else {
                        long blocks = event.getFileSize() / blockSize;
                        byte[] data = new byte[blockSize];
                        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                            for (long i = 0; i <= blocks; i++) {
                                if (i == blocks) {
                                    int lastBlockSize = (int) (event.getFileSize() % blockSize);
                                    if (0 != lastBlockSize) {
                                        byte[] lastBlock = new byte[lastBlockSize];
                                        raf.read(lastBlock);
                                        remoteMessageHandler.sendFileBlock(code, event.getFilePath(), i, blockSize, lastBlock);
                                    } else if (0 == blocks) {
                                        remoteMessageHandler.sendFileBlock(code, event.getFilePath(), 0, -1, EMPTY_BYTE);
                                    }
                                } else {
                                    raf.seek(i * blockSize);
                                    raf.read(data);
                                    remoteMessageHandler.sendFileBlock(code, event.getFilePath(), i, blockSize, data);
                                }
                            }
                        } catch (FileNotFoundException e) {
                            event.setEventType(EventType.FILE_DELETE);
                            remoteMessageHandler.sendEvent(event);
                        } catch (IOException e) {
                        }
                    }
                }
            } else {
                remoteMessageHandler.sendEvent(event);
            }
        }
    }

    /**
     * @return the remoteMessageHandler
     */
    public RemoteMessageHandler getRemoteMessageHandler() {
        return remoteMessageHandler;
    }

    @Override
    public void cache(FileEvent event) {
        eventQueue.add(event);
    }
}