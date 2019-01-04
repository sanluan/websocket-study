package org.microprofile.file.handler;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;

import org.apache.commons.io.FileUtils;
import org.microprofile.common.utils.EncodeUtils;
import org.microprofile.file.message.FileChecksum;
import org.microprofile.websocket.handler.Session;

public class LocalFileAdaptor {

    private String basePath;
    private int blockSize;
    private boolean running = true;

    public LocalFileAdaptor(String basePath, int blockSize) {
        super();
        this.basePath = basePath;
        this.blockSize = blockSize;
        new File(basePath).mkdirs();
    }

    /**
     * @param session
     * @param remoteMessageHandler
     */
    public void fileChecksum(Session session, RemoteMessageHandler remoteMessageHandler) {
        try {
            Files.walkFileTree(Paths.get(basePath), new ChecksumFilesVisitor(remoteMessageHandler, session, this));
        } catch (IOException e) {
        }
    }

    /**
     * @param file
     * @param checksum
     * @return
     * @throws FileNotFoundException
     * @throws IOException
     */
    public boolean verifyFile(File file, byte[] checksum) throws FileNotFoundException, IOException {
        if (null == checksum) {
            return false;
        }
        try (FileInputStream fin = new FileInputStream(file)) {
            MappedByteBuffer byteBuffer = fin.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, file.length());
            return Arrays.equals(EncodeUtils.md2(byteBuffer), checksum);
        }
    }

    /**
     * @param filePath
     * @param data
     * @param start
     * @return file length
     */
    public long createFile(String filePath, byte[] data, int start) {
        File file = getFile(filePath);
        if (file.exists() && file.isDirectory()) {
            file.delete();
        } else {
            file.getParentFile().mkdirs();
        }
        if (start >= data.length) {
            try {
                file.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw"); FileLock lock = raf.getChannel().lock()) {
                raf.setLength(data.length - start);
                raf.write(data, start, data.length - start);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return file.length();
    }

    /**
     * @param filePath
     * @param fileSize
     * @param blockIndex
     * @param blockSize
     * @param data
     * @param startIndex
     * @return file length
     */
    public long modifyFile(String filePath, long fileSize, int blockSize, int blockIndex, byte[] data, int startIndex) {
        File file = getFile(filePath);
        if (file.exists() && file.isDirectory()) {
            file.delete();
        } else {
            file.getParentFile().mkdirs();
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw"); FileLock lock = raf.getChannel().lock()) {
            raf.seek(blockIndex * blockSize);
            raf.write(data, startIndex, data.length - startIndex);
        } catch (IOException e) {
            e.printStackTrace();
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
        if (file.exists() && file.isDirectory()) {
            FileUtils.deleteQuietly(file);
        }
        return file.length();
    }

    /**
     * @param filePath
     * @return
     */
    public File getFile(String filePath) {
        return new File(basePath, filePath);
    }

    /**
     * @return the blockSize
     */
    public int getBlockSize() {
        return blockSize;
    }

    public String getRelativeFilePath(File file) {
        String absolutePath = file.getAbsolutePath();
        return absolutePath.substring(basePath.length() + 1, absolutePath.length());
    }

    public boolean isRootDir(File file) {
        String absolutePath = file.getAbsolutePath();
        return basePath.equalsIgnoreCase(absolutePath) || basePath.length() > absolutePath.length();
    }

    /**
     * @return the running
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * 
     */
    public void stop() {
        running = false;
    }

    private static class ChecksumFilesVisitor extends SimpleFileVisitor<Path> {

        private RemoteMessageHandler remoteMessageHandler;
        private Session session;
        private LocalFileAdaptor localFileAdaptor;

        public ChecksumFilesVisitor(RemoteMessageHandler remoteMessageHandler, Session session,
                LocalFileAdaptor localFileAdaptor) {
            this.remoteMessageHandler = remoteMessageHandler;
            this.session = session;
            this.localFileAdaptor = localFileAdaptor;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (localFileAdaptor.isRunning()) {
                File file = dir.toFile();
                if (!localFileAdaptor.isRootDir(file)) {
                    FileChecksum fileChecksum = new FileChecksum(localFileAdaptor.getRelativeFilePath(file), true, attrs.size());
                    remoteMessageHandler.sendFileChecksum(session, fileChecksum);
                }
                return FileVisitResult.CONTINUE;
            } else {
                return FileVisitResult.TERMINATE;
            }
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (localFileAdaptor.isRunning()) {
                File f = file.toFile();
                FileChecksum fileChecksum = new FileChecksum(localFileAdaptor.getRelativeFilePath(f), false, attrs.size());
                if (0 < attrs.size() && attrs.size() < localFileAdaptor.getBlockSize() * 100) {
                    try (FileInputStream fin = new FileInputStream(f)) {
                        MappedByteBuffer byteBuffer = fin.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, attrs.size());
                        byte[] checksum = EncodeUtils.md2(byteBuffer);
                        if (null != checksum) {
                            fileChecksum.setChecksum(checksum);
                            remoteMessageHandler.sendFileChecksum(session, fileChecksum);
                        }
                    } catch (FileNotFoundException e) {
                    } catch (Exception e) {
                    }
                } else {
                    remoteMessageHandler.sendFileChecksum(session, fileChecksum);
                }
                return FileVisitResult.CONTINUE;
            } else {
                return FileVisitResult.TERMINATE;
            }
        }
    }
}