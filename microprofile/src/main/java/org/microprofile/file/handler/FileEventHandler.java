package org.microprofile.file.handler;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.microprofile.common.utils.EncodeUtils;
import org.microprofile.common.utils.EventQueue;
import org.microprofile.file.constant.Constants;
import org.microprofile.file.event.EventHandler;
import org.microprofile.file.event.FileEvent;
import org.microprofile.file.event.FileEvent.EventType;
import org.microprofile.file.listener.FileListener;
import org.microprofile.file.message.FileBlockChecksumListMessage.FileBlockChecksum;

public class FileEventHandler implements EventHandler {
    private FileListener fileListener;
    private EventQueue<FileEvent> eventQueue;
    private FileMessageHandler fileMessageHandler;

    public FileEventHandler(int cacheSize, FileListener fileListener, FileMessageHandler fileMessageHandler) {
        super();
        this.eventQueue = new EventQueue<>(cacheSize);
        this.fileMessageHandler = fileMessageHandler;
        this.fileListener = fileListener;
    }

    @Override
    public void process(FileEvent event) {
        if (eventQueue.contains(event)) {
            eventQueue.remove(event);
        } else {
            byte code = event.getEventType().getCode();
            if (0 == code || 1 == code) {
                File file = new File(fileListener.getBasePath(), event.getFilePath());
                if (Constants.DEFULT_BLOCK_SIZE < event.getFileSize()) {
                    try {
                        fileMessageHandler.sendFileBlock(code, event.getFilePath(), 0, -1, FileUtils.readFileToByteArray(file));
                    } catch (IOException e) {
                    }
                } else {
                    if (1 == code) {
                        List<FileBlockChecksum> fileList = new LinkedList<>();
                        long blocks = event.getFileSize() / Constants.DEFULT_BLOCK_SIZE;
                        byte[] data = new byte[Constants.DEFULT_BLOCK_SIZE];
                        for (long i = 0; i < blocks; i++) {
                            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                                raf.seek(i * Constants.DEFULT_BLOCK_SIZE);
                                raf.read(data);
                                fileList.add(new FileBlockChecksum(i, EncodeUtils.md2(data)));
                                if (i == blocks - 1) {
                                    int lastBlockSize = (int) (event.getFileSize() % Constants.DEFULT_BLOCK_SIZE);
                                    if (0 != lastBlockSize) {
                                        byte[] lastBlock = new byte[lastBlockSize];
                                        raf.read(lastBlock);
                                        fileList.add(new FileBlockChecksum(i + 1, EncodeUtils.md2(lastBlock)));
                                    }
                                }
                            } catch (FileNotFoundException e) {
                                event.setEventType(EventType.FILE_DELETE);
                                fileMessageHandler.sendEvent(event);
                            } catch (IOException e) {
                            }
                        }
                    } else {
                        long blocks = event.getFileSize() / Constants.DEFULT_BLOCK_SIZE;
                        byte[] data = new byte[Constants.DEFULT_BLOCK_SIZE];
                        for (long i = 0; i < blocks; i++) {
                            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                                raf.seek(i * Constants.DEFULT_BLOCK_SIZE);
                                raf.read(data);
                                fileMessageHandler.sendFileBlock(code, event.getFilePath(), i, Constants.DEFULT_BLOCK_SIZE, data);
                                if (i == blocks - 1) {
                                    int lastBlockSize = (int) (event.getFileSize() % Constants.DEFULT_BLOCK_SIZE);
                                    if (0 != lastBlockSize) {
                                        byte[] lastBlock = new byte[lastBlockSize];
                                        raf.read(lastBlock);
                                        fileMessageHandler.sendFileBlock(code, event.getFilePath(), i + 1,
                                                Constants.DEFULT_BLOCK_SIZE, lastBlock);
                                    }
                                }
                            } catch (FileNotFoundException e) {
                                event.setEventType(EventType.FILE_DELETE);
                                fileMessageHandler.sendEvent(event);
                            } catch (IOException e) {
                            }
                        }
                    }
                }
            } else {
                fileMessageHandler.sendEvent(event);
            }
        }
    }

    @Override
    public void cache(FileEvent event) {
        eventQueue.add(event);
    }
}