package org.microprofile.file.handler;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.microprofile.common.utils.EncodeUtils;
import org.microprofile.file.message.FileChecksum;
import org.microprofile.websocket.handler.Session;

public class LocalFileAdaptor {

    private final Log log = LogFactory.getLog(getClass());

    private String basePath;
    private int blockSize;
    private boolean running = true;

    private Map<String, FileChecksumCache> cache = new HashMap<>();

    public LocalFileAdaptor(String basePath, int blockSize) {
        super();
        // 统一去掉末尾分隔符：getRelativeFilePath 依赖 basePath 的长度算相对路径，
        // 多一个分隔符就会把文件名的第一个字符截掉
        this.basePath = trimSeparator(basePath);
        this.blockSize = blockSize;
        new File(this.basePath).mkdirs();
    }

    private static String trimSeparator(String path) {
        if (null == path) {
            return null;
        }
        String result = path;
        while (2 <= result.length() && ('/' == result.charAt(result.length() - 1) || '\\' == result.charAt(result.length() - 1))) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /**
     * @param session
     * @param remoteMessageHandler
     */
    public void fileChecksum(Session session, RemoteMessageHandler remoteMessageHandler) {
        try {
            Files.walkFileTree(Paths.get(basePath), new ChecksumFilesVisitor(remoteMessageHandler, cache, session, this));
        } catch (IOException e) {
            log.error("walk file tree failed:" + basePath, e);
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
        return Arrays.equals(checksum(file, 0, (int) file.length()), checksum);
    }

    /**
     * 计算文件某一段的 MD2 校验和。
     * <p>
     * 不使用 {@code FileChannel.map}：Windows 上被映射过的文件在映射释放前会拒绝写操作
     * （"请求的操作无法在使用用户映射区域打开的文件上执行"），
     * 于是校验和刚算完，用户或同步程序再写同一个文件就会失败。这里改成流式读取。
     * </p>
     *
     * @param file
     * @param offset
     * @param length
     * @return
     * @throws IOException
     */
    public static byte[] checksum(File file, long offset, int length) throws IOException {
        if (0 >= length) {
            return EncodeUtils.md2(new byte[0]);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("MD2");
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.seek(offset);
                byte[] buffer = new byte[8192];
                long remaining = length;
                while (0 < remaining) {
                    int read = raf.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (0 > read) {
                        break;
                    }
                    digest.update(buffer, 0, read);
                    remaining -= read;
                }
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            // 与 EncodeUtils 的降级语义保持一致：算法不可用时退回原始字节
            byte[] data = new byte[length];
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.seek(offset);
                raf.readFully(data);
            }
            return EncodeUtils.md2(data);
        }
    }

    /**
     * @param filePath
     * @param data
     * @param start
     * @return file length
     */
    public File createFile(String filePath, byte[] data, int start) {
        File file = getFile(filePath);
        if (file.exists() && file.isDirectory()) {
            FileUtils.deleteQuietly(file);
        } else {
            mkdirs(file);
        }
        int contentLength = start >= data.length ? 0 : data.length - start;
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw");) {
            // 整文件写入必须从偏移 0 开始。原实现 seek(data.length - start)，
            // 等于把内容写到“内容长度”这个偏移上，新建文件前面会多出一段等长空洞
            raf.seek(0);
            if (0 < contentLength) {
                raf.write(data, start, contentLength);
            }
            if (raf.length() > contentLength) {
                raf.setLength(contentLength);
            }
        } catch (IOException e) {
            log.error("create file failed:" + filePath, e);
        }
        return file;
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
    public File modifyFile(String filePath, long fileSize, int blockSize, int blockIndex, byte[] data, int startIndex) {
        File file = getFile(filePath);
        if (file.exists() && file.isDirectory()) {
            FileUtils.deleteQuietly(file);
        } else {
            mkdirs(file);
        }
        int contentLength = startIndex >= data.length ? 0 : data.length - startIndex;
        // blockSize 为 -1 表示整文件写入；同时把偏移换算成 long，避免大文件下标溢出
        long baseOffset = blockSize > 0 ? (long) blockIndex * blockSize : 0L;
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw");) {
            raf.seek(baseOffset);
            if (0 < contentLength) {
                raf.write(data, startIndex, contentLength);
            }
            long end = baseOffset + contentLength;
            // 本次写入已到达文件末尾时按对端长度截断，避免旧文件的长尾残留（原实现只写不截）
            if (0 <= fileSize && end >= fileSize && raf.length() > fileSize) {
                raf.setLength(fileSize);
            }
        } catch (IOException e) {
            log.error("modify file failed:" + filePath, e);
        }
        return file;
    }

    private void mkdirs(File file) {
        File parent = file.getParentFile();
        if (null != parent) {
            parent.mkdirs();
        }
    }

    /**
     * @param filePath
     * @return file length
     */
    public File deleteFile(String filePath) {
        File file = getFile(filePath);
        FileUtils.deleteQuietly(file);
        return file;
    }

    /**
     * @param filePath
     * @return file length
     */
    public File createDirectory(String filePath) {
        File file = getFile(filePath);
        file.mkdirs();
        return file;
    }

    /**
     * @param filePath
     * @return file length
     */
    public File deleteDirectory(String filePath) {
        File file = getFile(filePath);
        if (file.exists() && file.isDirectory()) {
            FileUtils.deleteQuietly(file);
        }
        return file;
    }

    /**
     * @param filePath
     * @return
     */
    public File getFile(String filePath) {
        return new File(basePath, filePath);
    }

    /**
     * @return the basePath 同步根目录
     */
    public String getBasePath() {
        return basePath;
    }

    /**
     * @return the blockSize
     */
    public int getBlockSize() {
        return blockSize;
    }

    public String getRelativeFilePath(File file) {
        String absolutePath = file.getAbsolutePath();
        if (null == basePath || absolutePath.length() <= basePath.length()) {
            return "";
        }
        char next = absolutePath.charAt(basePath.length());
        if ('/' == next || '\\' == next || File.separatorChar == next) {
            return absolutePath.substring(basePath.length() + 1);
        }
        return absolutePath.substring(basePath.length());
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

    private static class FileChecksumCache {
        private FileChecksum fileChecksum;
        private long lastModify;

        /**
         * @return the fileChecksum
         */
        public FileChecksum getFileChecksum() {
            return fileChecksum;
        }

        /**
         * @param fileChecksum
         *            the fileChecksum to set
         */
        public void setFileChecksum(FileChecksum fileChecksum) {
            this.fileChecksum = fileChecksum;
        }

        /**
         * @return the lastModify
         */
        public long getLastModify() {
            return lastModify;
        }

        /**
         * @param lastModify
         *            the lastModify to set
         */
        public void setLastModify(long lastModify) {
            this.lastModify = lastModify;
        }
    }

    private static class ChecksumFilesVisitor extends SimpleFileVisitor<Path> {

        private RemoteMessageHandler remoteMessageHandler;
        private Session session;
        private Map<String, FileChecksumCache> cache;
        private LocalFileAdaptor localFileAdaptor;

        public ChecksumFilesVisitor(RemoteMessageHandler remoteMessageHandler, Map<String, FileChecksumCache> cache,
                Session session, LocalFileAdaptor localFileAdaptor) {
            this.remoteMessageHandler = remoteMessageHandler;
            this.cache = cache;
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
                String relativeFilePath = localFileAdaptor.getRelativeFilePath(f);
                if (!remoteMessageHandler.canSend(relativeFilePath, attrs.size(), f.lastModified())) {
                    // 本端广播规则排除的文件：对账时根本不对外公告（也不算校验和，省一次读盘）
                    return FileVisitResult.CONTINUE;
                }
                FileChecksum fileChecksum;
                FileChecksumCache fileChecksumCache = cache.get(relativeFilePath);
                if (null == fileChecksumCache || fileChecksumCache.getLastModify() != f.lastModified()) {
                    fileChecksum = new FileChecksum(relativeFilePath, false, attrs.size());
                    if (0 < attrs.size() && attrs.size() < localFileAdaptor.getBlockSize() * 100) {
                        try {
                            fileChecksum.setChecksum(checksum(f, 0, (int) attrs.size()));
                        } catch (Exception e) {
                        }
                    }
                    if (null == fileChecksumCache) {
                        fileChecksumCache = new FileChecksumCache();
                    }
                    fileChecksumCache.setLastModify(f.lastModified());
                    fileChecksumCache.setFileChecksum(fileChecksum);
                    cache.put(relativeFilePath, fileChecksumCache);
                } else {
                    fileChecksum = fileChecksumCache.getFileChecksum();
                }
                remoteMessageHandler.sendFileChecksum(session, fileChecksum);
                return FileVisitResult.CONTINUE;
            } else {
                return FileVisitResult.TERMINATE;
            }
        }
    }
}