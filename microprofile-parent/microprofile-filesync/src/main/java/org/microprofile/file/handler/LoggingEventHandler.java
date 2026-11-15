package org.microprofile.file.handler;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.microprofile.file.event.EventHandler;
import org.microprofile.file.event.FileEvent;
import org.microprofile.file.event.NodeInfo;

/**
 * 日志记录处理器：把经过 {@link EventHandler} 的文件事件记到日志，并按类型累计计数。
 * <p>
 * 用法（可与 {@code FileEventHandler} 同时挂在同一个监听器上，互不影响）：
 *
 * <pre>
 * FileListener listener = new FileListener(&quot;D:/aaa&quot;);
 * FileEventHandler handler = new FileEventHandler(listener.getLocalFileAdaptor());
 * listener.addEventHandler(handler);
 * listener.addEventHandler(new LoggingEventHandler());
 * </pre>
 *
 * </p>
 * <p>
 * {@link #handle(FileEvent)} 记 info：文件系统发生的每一次变更（本地产生的、以及被对端写入后本地
 * 监听再报出来的），都会走到这里。
 * {@link #cache(FileEvent)} 记 debug：它是回环抑制的登记入口，只有 {@code FileEventHandler} 会收到，
 * 同一监听器上的兄弟处理器不会收到——所以作为兄弟挂载时，对端写入引起的事件是通过
 * {@link #handle(FileEvent)} 记录下来的，不会丢。
 * </p>
 */
public class LoggingEventHandler implements EventHandler {

    public final static String DEFULT_PREFIX = "[filesync]";

    protected final Log log;
    private final String prefix;
    private final AtomicLong handledCount = new AtomicLong();
    private final AtomicLong cachedCount = new AtomicLong();
    private final Map<FileEvent.EventType, AtomicLong> handledCounters = new ConcurrentHashMap<>();

    /**
     */
    public LoggingEventHandler() {
        this(LogFactory.getLog(LoggingEventHandler.class), DEFULT_PREFIX);
    }

    /**
     * @param prefix
     *            日志前缀
     */
    public LoggingEventHandler(String prefix) {
        this(LogFactory.getLog(LoggingEventHandler.class), prefix);
    }

    /**
     * @param log
     * @param prefix
     */
    public LoggingEventHandler(Log log, String prefix) {
        super();
        this.log = log;
        this.prefix = null == prefix ? DEFULT_PREFIX : prefix;
    }

    @Override
    public void handle(FileEvent event) {
        if (null == event) {
            return;
        }
        handledCount.incrementAndGet();
        if (null != event.getEventType()) {
            AtomicLong counter = handledCounters.get(event.getEventType());
            if (null == counter) {
                handledCounters.put(event.getEventType(), new AtomicLong(1));
            } else {
                counter.incrementAndGet();
            }
        }
        if (log.isInfoEnabled()) {
            log.info(describe("event", event));
        }
    }

    @Override
    public void cache(FileEvent event) {
        if (null == event) {
            return;
        }
        cachedCount.incrementAndGet();
        if (log.isDebugEnabled()) {
            log.debug(describe("remote-echo", event));
        }
    }

    /**
     * @return 已记录（本地）事件总数
     */
    public long getHandledCount() {
        return handledCount.get();
    }

    /**
     * @return 已登记的回环抑制事件数
     */
    public long getCachedCount() {
        return cachedCount.get();
    }

    /**
     * @param eventType
     * @return 指定类型的本地事件数
     */
    public long getHandledCount(FileEvent.EventType eventType) {
        AtomicLong counter = handledCounters.get(eventType);
        return null == counter ? 0L : counter.get();
    }

    /**
     * @return 各类事件的计数摘要，便于直接打印/上报
     */
    public String getSummary() {
        StringBuilder builder = new StringBuilder();
        builder.append(prefix).append(" handled=").append(handledCount.get()).append(" cached=").append(cachedCount.get());
        for (FileEvent.EventType eventType : FileEvent.EventType.values()) {
            builder.append(' ').append(eventType.name()).append('=').append(getHandledCount(eventType));
        }
        return builder.toString();
    }

    private String describe(String action, FileEvent event) {
        StringBuilder builder = new StringBuilder(96);
        builder.append(prefix).append(' ').append(action).append(' ').append(event.getEventType());
        builder.append(" path=").append(event.getFilePath());
        builder.append(" size=").append(event.getFileSize());
        builder.append(" time=").append(format(event.getLastModify()));
        if (null != event.getEventType() && event.getEventType().isDirectory()) {
            builder.append(" directory");
        }
        builder.append(" source=").append(null == event.getNodeInfo() ? "unknown" : event.getNodeInfo().describe());
        if (null != event.getRelayers() && !event.getRelayers().isEmpty()) {
            builder.append(" relayedBy=");
            int index = 0;
            for (NodeInfo relayer : event.getRelayers()) {
                if (null == relayer) {
                    continue;
                }
                if (0 < index++) {
                    builder.append(',');
                }
                builder.append(relayer.describe());
            }
        }
        return builder.toString();
    }

    private String format(long millis) {
        if (0 >= millis) {
            return "-";
        }
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(millis));
        } catch (RuntimeException e) {
            return String.valueOf(millis);
        }
    }
}
