package org.microprofile.file.archive;

import java.util.ArrayList;
import java.util.List;

/**
 * 合并/导出结果：导出了哪些文件、哪些按删除处理、哪些无法重建。
 */
public class MergeReport {

    private final long cutoff;
    private final List<String> exported = new ArrayList<>();
    private final List<String> deleted = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private long bytes;

    public MergeReport(long cutoff) {
        super();
        this.cutoff = cutoff;
    }

    /**
     * @param path
     * @param size
     */
    public void addExported(String path, long size) {
        exported.add(path);
        bytes += size;
    }

    /**
     * @param path
     */
    public void addDeleted(String path) {
        deleted.add(path);
    }

    /**
     * @param warning
     */
    public void addWarning(String warning) {
        warnings.add(warning);
    }

    /**
     * @return 合并截止时间（毫秒）
     */
    public long getCutoff() {
        return cutoff;
    }

    /**
     * @return 导出的文件数
     */
    public int getExportedCount() {
        return exported.size();
    }

    /**
     * @return 导出总量（字节）
     */
    public long getBytes() {
        return bytes;
    }

    /**
     * @return 在该时间点上已被删除、因此没有导出的路径
     */
    public List<String> getDeleted() {
        return deleted;
    }

    /**
     * @return 导出的文件路径
     */
    public List<String> getExported() {
        return exported;
    }

    /**
     * @return 警告（例如某文件缺少基线全量，无法重建）
     */
    public List<String> getWarnings() {
        return warnings;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("exported=").append(exported.size()).append(" bytes=").append(bytes);
        builder.append(" deleted=").append(deleted.size()).append(" warnings=").append(warnings.size());
        if (!deleted.isEmpty()) {
            builder.append(" deletedPaths=").append(deleted);
        }
        for (String warning : warnings) {
            builder.append(" [warn]").append(warning);
        }
        return builder.toString();
    }
}
