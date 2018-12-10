package org.microprofile.file.handler;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

import org.apache.commons.io.FileUtils;

public class LocalFileAdaptor {
    private String basePath;

    public LocalFileAdaptor(String basePath) {
        super();
        this.basePath = basePath;
    }

    /**
     * @param filePath
     * @param data
     * @param start
     * @return file length
     */
    public long createFile(String filePath, byte[] data, int start) {
        File file = getFile(filePath);
        try {
            if (start >= data.length) {
                file.createNewFile();
            } else {
                FileUtils.writeByteArrayToFile(file, data, start, data.length);
            }
        } catch (IOException e) {
        }
        return file.length();
    }

    /**
     * @param filePath
     * @param blockIndex
     * @param blockSize
     * @param data
     * @param startIndex
     * @return file length
     */
    public long modifyFile(String filePath, long blockIndex, long blockSize, byte[] data, int startIndex) {
        File file = getFile(filePath);
        try {
            if (0 < blockSize) {
                FileUtils.writeByteArrayToFile(file, data);
            } else {
                try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                    raf.seek(blockIndex * blockSize);
                    raf.write(data, startIndex, data.length - startIndex);
                }
            }
        } catch (IOException e) {
        }
        return file.length();
    }

    /**
     * @param filePath
     * @return file length
     */
    public long deleteFile(String filePath) {
        File file = getFile(filePath);
        FileUtils.deleteQuietly(file);
        return file.length();
    }

    /**
     * @param filePath
     * @return file length
     */
    public long createDirectory(String filePath) {
        File file = getFile(filePath);
        file.mkdirs();
        return file.length();
    }

    /**
     * @param filePath
     * @return file length
     */
    public long deleteDirectory(String filePath) {
        File file = getFile(filePath);
        FileUtils.deleteQuietly(file);
        return file.length();
    }

    public File getFile(String filePath) {
        return new File(basePath, filePath);
    }

}