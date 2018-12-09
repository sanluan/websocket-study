package org.microprofile.file.listener;

import java.io.File;
import java.io.FileFilter;

import org.apache.commons.io.IOCase;
import org.apache.commons.io.monitor.FileAlterationListener;
import org.apache.commons.io.monitor.FileAlterationMonitor;
import org.apache.commons.io.monitor.FileAlterationObserver;
import org.microprofile.file.event.EventHandler;
import org.microprofile.file.event.FileEvent;
import org.microprofile.file.event.FileEvent.EventType;

/**
 * FileListener
 * 
 */
public class FileListener implements FileAlterationListener {
    private String basePath;
    private EventHandler eventHandler;
    private FileAlterationObserver observer;
    private FileAlterationMonitor fileMonitor;

    /**
     * @param listenPath
     * @param eventHandlers
     */
    public FileListener(String listenPath, EventHandler eventHandler) {
        this(new FileAlterationObserver(listenPath), eventHandler);
    }

    /**
     * @param listenPath
     * @param fileFilter
     * @param eventHandlers
     */
    public FileListener(String listenPath, final FileFilter fileFilter, EventHandler eventHandler) {
        this(new FileAlterationObserver(listenPath, fileFilter), eventHandler);
    }

    /**
     * @param listenPath
     * @param fileFilter
     * @param caseSensitivity
     * @param eventHandlers
     */
    public FileListener(String listenPath, final FileFilter fileFilter, final IOCase caseSensitivity, EventHandler eventHandler) {
        this(new FileAlterationObserver(listenPath, fileFilter, caseSensitivity), eventHandler);
    }

    private FileListener(FileAlterationObserver observer, EventHandler eventHandler) {
        super();
        if (null == eventHandler) {
            throw new IllegalArgumentException("event handler canb't be null");
        }
        this.observer = observer;
        this.eventHandler = eventHandler;
        init();
    }

    /**
     * @return the basePath
     */
    public String getBasePath() {
        return basePath;
    }

    private void init() {
        this.basePath = observer.getDirectory().getAbsolutePath();
        this.observer.addListener(this);
        this.fileMonitor = new FileAlterationMonitor();
        this.fileMonitor.addObserver(observer);
    }

    private void process(EventType eventType, File file) {
        String absolutePath = file.getAbsolutePath();
        String filePath = absolutePath.substring(basePath.length() + 1, absolutePath.length());
        eventHandler.process(new FileEvent(eventType, filePath, file.length()));
    }

    @Override
    public void onFileCreate(File file) {
        process(EventType.FILE_CREATE, file);
    }

    @Override
    public void onFileChange(File file) {
        process(EventType.FILE_MODIFY, file);
    }

    @Override
    public void onFileDelete(File file) {
        process(EventType.FILE_DELETE, file);
    }

    @Override
    public void onDirectoryCreate(File directory) {
        process(EventType.DIRECTORY_CREATE, directory);
    }

    @Override
    public void onDirectoryChange(File directory) {
    }

    @Override
    public void onDirectoryDelete(File directory) {
        process(EventType.DIRECTORY_DELETE, directory);
    }

    @Override
    public void onStart(FileAlterationObserver observer) {

    }

    @Override
    public void onStop(FileAlterationObserver observer) {
    }

    /**
     * @throws Exception
     */
    public void start() throws Exception {
        this.fileMonitor.start();
    }

    /**
     * @throws Exception
     */
    public void stop() throws Exception {
        this.fileMonitor.stop();
    }

}
