package org.microprofile.file.handler;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.microprofile.common.utils.EncodeUtils;
import org.microprofile.file.message.FileChecksumMessage.FileChecksum;

public class LocalFileAdaptor {
    private String basePath;

    public LocalFileAdaptor(String basePath) {
        super();
        this.basePath = basePath;
    }

    public List<FileChecksum> getFileChecksumList() {
        List<FileChecksum> result = new LinkedList<>();
        try {
            Files.walkFileTree(Paths.get(basePath), new FilterFilesVisitor(result, this));
        } catch (IOException e) {
        }
        return result;
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

    public String getRelativeFilePath(File file) {
        String absolutePath = file.getAbsolutePath();
        return absolutePath.substring(basePath.length() + 1, absolutePath.length());
    }

    private static class FilterFilesVisitor extends SimpleFileVisitor<Path> {

        private List<FileChecksum> result;
        private LocalFileAdaptor localFileAdaptor;

        public FilterFilesVisitor(List<FileChecksum> result, LocalFileAdaptor localFileAdaptor) {
            this.result = result;
            this.localFileAdaptor = localFileAdaptor;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            FileChecksum fileChecksum = new FileChecksum(localFileAdaptor.getRelativeFilePath(dir.toFile()), false, attrs.size());
            result.add(fileChecksum);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            File f = file.toFile();
            FileChecksum fileChecksum = new FileChecksum(localFileAdaptor.getRelativeFilePath(f), attrs.isDirectory(),
                    attrs.size());
            if (0 < attrs.size()) {
                FileInputStream fin = null;
                try {
                    fin = new FileInputStream(f);
                    MappedByteBuffer byteBuffer = fin.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, attrs.size());
                    byte[] checksum = EncodeUtils.md2(byteBuffer);
                    if (null != checksum) {
                        fileChecksum.setChecksum(checksum);
                        result.add(fileChecksum);
                    }
                } catch (FileNotFoundException e) {
                } catch (Exception e) {
                } finally {
                    if (null != fin) {
                        try {
                            fin.close();
                        } catch (IOException e) {
                        }
                    }
                }
            } else {
                result.add(fileChecksum);
            }
            return FileVisitResult.CONTINUE;
        }
    }
}