package org.microprofile.file.handler;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.microprofile.file.event.EventHandler;
import org.microprofile.file.event.FileEvent;
import org.microprofile.file.event.FileEvent.EventType;

public class LocalFileAdaptor {
    private List<EventHandler> eventHandlers;
    private String basePath;

    public LocalFileAdaptor(String basePath) {
        super();
        this.basePath = basePath;
    }

    /**
     * @param filePath
     * @param data
     * @param start
     */
    public void createFile(String filePath, byte[] data, int start) {
        try {
            if (start >= data.length) {
                getFile(filePath).createNewFile();
            } else {
                FileUtils.writeByteArrayToFile(getFile(filePath), data, start, data.length);
            }
            cache(new FileEvent(EventType.FILE_CREATE, filePath, 0));
        } catch (IOException e) {
        }
    }

    /**
     * @param filePath
     * @param blockIndex
     * @param blockSize
     * @param data
     * @param start
     */
    public void modifyFile(String filePath, long blockIndex, long blockSize, byte[] data, int start) {
        try {
            File file = getFile(filePath);
            if (0 < blockSize) {
                FileUtils.writeByteArrayToFile(file, data);
            } else {
                try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                    raf.seek(blockIndex * blockSize);
                    raf.write(data, start, data.length - start);
                }
            }
            cache(new FileEvent(EventType.FILE_MODIFY, filePath, 0));
        } catch (IOException e) {
        }
    }

    /**
     * @param filePath
     */
    public void deleteFile(String filePath) {
        FileUtils.deleteQuietly(getFile(filePath));
        cache(new FileEvent(EventType.FILE_DELETE, filePath, 0));
    }

    /**
     * @param filePath
     */
    public void createDirectory(String filePath) {
        getFile(filePath).mkdirs();
        cache(new FileEvent(EventType.DIRECTORY_CREATE, filePath, 0));
    }

    /**
     * @param filePath
     */
    public void deleteDirectory(String filePath) {
        FileUtils.deleteQuietly(getFile(filePath));
        cache(new FileEvent(EventType.DIRECTORY_DELETE, filePath, 0));
    }

    public File getFile(String filePath) {
        return new File(basePath, filePath);
    }

    /**
     * @param fileEventHandler
     *            the fileEventHandler to set
     */
    public void addEventHandler(EventHandler eventHandler) {
        if (null != eventHandler) {
            if (null == this.eventHandlers) {
                this.eventHandlers = new LinkedList<>();
            }
            this.eventHandlers.add(eventHandler);
        }
    }

    private void cache(FileEvent event) {
        if (null != eventHandlers) {
            for (EventHandler eventHandler : eventHandlers) {
                eventHandler.cache(event);
            }
        }
    }
}