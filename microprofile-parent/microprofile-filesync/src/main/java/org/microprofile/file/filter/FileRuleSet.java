package org.microprofile.file.filter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一端（一个节点）的一组文件规则：白名单 + 黑名单，用于"本端广播哪些文件"和"本端接收哪些文件"。
 * <p>
 * 判定语义（<b>白名单优先级最高</b>）：
 * </p>
 * <ul>
 * <li>一条规则都没配（默认，等价于 {@link #EMPTY}）→ <b>全部文件都放行</b>；</li>
 * <li>配了白名单 → 只有命中白名单的才放行；即使同时命中黑名单也照样放行（白名单优先）；</li>
 * <li>只配了黑名单 → 命中黑名单的排除，其余全部放行。</li>
 * </ul>
 * <p>
 * 规则对象是不可变的：{@link #withWhitelist(FileRule...)} / {@link #withBlacklist(FileRule...)} 返回新实例，
 * 所以可以在运行中整体替换（{@code handler.setSendRules(...)} 是 volatile 字段赋值）。
 * </p>
 *
 * <pre>
 * // 本端只广播 .txt、.xml，且不广播 1MB 以上的文件
 * FileRuleSet send = FileRuleSet.ofWhitelist(FileRule.ofPattern("*.txt"), FileRule.ofPattern("*.xml"));
 * // 本端不接收 .tmp、临时目录
 * FileRuleSet receive = FileRuleSet.ofBlacklist(FileRule.ofPattern("*.tmp"),
 *         FileRule.ofPattern("**&#47;tmp/&#42;&#42;"));
 * </pre>
 */
public class FileRuleSet {

    /**
     * 空规则集：没有配置任何规则 → 全部文件放行
     */
    public static final FileRuleSet EMPTY = new FileRuleSet(Collections.<FileRule> emptyList(),
            Collections.<FileRule> emptyList());

    private final List<FileRule> whitelist;
    private final List<FileRule> blacklist;

    private FileRuleSet(List<FileRule> whitelist, List<FileRule> blacklist) {
        super();
        this.whitelist = whitelist;
        this.blacklist = blacklist;
    }

    private static List<FileRule> copy(FileRule... rules) {
        List<FileRule> list = new ArrayList<>();
        if (null != rules) {
            for (FileRule rule : rules) {
                if (null != rule) {
                    list.add(rule);
                }
            }
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * @param rules
     *            白名单规则
     * @return 只带白名单的规则集
     */
    public static FileRuleSet ofWhitelist(FileRule... rules) {
        return EMPTY.withWhitelist(rules);
    }

    /**
     * @param rules
     *            黑名单规则
     * @return 只带黑名单的规则集
     */
    public static FileRuleSet ofBlacklist(FileRule... rules) {
        return EMPTY.withBlacklist(rules);
    }

    /**
     * @param rules
     *            追加的白名单规则
     * @return 新实例（原实例不变）
     */
    public FileRuleSet withWhitelist(FileRule... rules) {
        List<FileRule> list = new ArrayList<>(this.whitelist);
        list.addAll(copy(rules));
        return new FileRuleSet(Collections.unmodifiableList(list), this.blacklist);
    }

    /**
     * @param rules
     *            追加的黑名单规则
     * @return 新实例（原实例不变）
     */
    public FileRuleSet withBlacklist(FileRule... rules) {
        List<FileRule> list = new ArrayList<>(this.blacklist);
        list.addAll(copy(rules));
        return new FileRuleSet(this.whitelist, Collections.unmodifiableList(list));
    }

    /**
     * 判断一个文件是否放行。
     *
     * @param filePath
     *            相对路径
     * @param size
     *            文件大小，{@link FileRule#UNSET} 表示未知
     * @param lastModified
     *            最后修改时间，{@link FileRule#UNSET} 表示未知
     * @return 放行返回 true
     */
    public boolean matches(String filePath, long size, long lastModified) {
        if (!whitelist.isEmpty()) {
            // 白名单优先：命中即放行，不再看黑名单
            return anyMatch(whitelist, filePath, size, lastModified);
        }
        if (!blacklist.isEmpty()) {
            return !anyMatch(blacklist, filePath, size, lastModified);
        }
        // 没配任何规则：全部文件
        return true;
    }

    private static boolean anyMatch(List<FileRule> rules, String filePath, long size, long lastModified) {
        for (FileRule rule : rules) {
            if (rule.matches(filePath, size, lastModified)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return 是否一条规则都没配（即全部文件放行）
     */
    public boolean isEmpty() {
        return whitelist.isEmpty() && blacklist.isEmpty();
    }

    /**
     * @return 是否配置了白名单
     */
    public boolean hasWhitelist() {
        return !whitelist.isEmpty();
    }

    /**
     * @return 白名单规则（只读）
     */
    public List<FileRule> getWhitelist() {
        return whitelist;
    }

    /**
     * @return 黑名单规则（只读）
     */
    public List<FileRule> getBlacklist() {
        return blacklist;
    }

    /**
     * @return 规则的可读描述，用于日志
     */
    public String describe() {
        if (isEmpty()) {
            return "all";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("whitelist=").append(join(whitelist));
        builder.append(" blacklist=").append(join(blacklist));
        return builder.toString();
    }

    private static String join(List<FileRule> rules) {
        if (rules.isEmpty()) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < rules.size(); i++) {
            if (0 < i) {
                builder.append(", ");
            }
            builder.append(rules.get(i).describe());
        }
        return builder.append(']').toString();
    }

    @Override
    public String toString() {
        return describe();
    }
}
