package org.microprofile.file.handler;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

import org.apache.commons.io.FileUtils;
import org.microprofile.file.event.FileEvent;
import org.microprofile.file.event.FileEvent.EventType;

public class LocalFileHandler {
    private FileEventHandler fileEventHandler;
    private String basePath;

    public LocalFileHandler(FileEventHandler fileEventHandler, String basePath) {
        super();
        this.fileEventHandler = fileEventHandler;
        this.basePath = basePath;
    }

    public void createFile(String filePath, byte[] data, int start) {
        try {
            FileUtils.writeByteArrayToFile(new File(basePath, filePath), data, start, data.length);
            cache(new FileEvent(EventType.FILE_CREATE, filePath, 0));
        } catch (IOException e) {
        }
    }

    public void modifyFile(String filePath, long blockIndex, long blockSize, byte[] data, int start) {
        try {
            File file = new File(basePath, filePath);
            if (0 < blockSize) {
                FileUtils.writeByteArrayToFile(file, data);
            } else {
                try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                    raf.seek(blockIndex * blockSize);
                    raf.write(data, start, data.length);
                }
            }
            cache(new FileEvent(EventType.FILE_MODIFY, filePath, 0));
        } catch (IOException e) {
        }
    }

    public void deleteFile(String filePath) {
        FileUtils.deleteQuietly(new File(basePath, filePath));
        cache(new FileEvent(EventType.FILE_DELETE, filePath, 0));
    }

    public void createDirectory(String filePath) {
        new File(basePath, filePath).mkdirs();
        cache(new FileEvent(EventType.DIRECTORY_CREATE, filePath, 0));
    }

    public void deleteDirectory(String filePath) {
        FileUtils.deleteQuietly(new File(basePath, filePath));
        cache(new FileEvent(EventType.DIRECTORY_DELETE, filePath, 0));
    }

    private void cache(FileEvent event) {
        fileEventHandler.cache(event);
    }
}