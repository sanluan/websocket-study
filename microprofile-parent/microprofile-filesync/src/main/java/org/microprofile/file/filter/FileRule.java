package org.microprofile.file.filter;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.regex.Pattern;

/**
 * 一条文件匹配规则：路径表达式、大小范围、修改时间范围，三个条件之间是"且"的关系（都满足才算命中）。
 * <p>
 * 路径表达式默认按 <b>通配符</b>（glob）解释：{@code *} 匹配文件名里的一段（不跨目录），{@code **} 跨目录，
 * {@code ?} 匹配一个字符；以 {@code regex:} 开头则按正则表达式处理。
 * </p>
 *
 * <pre>
 * FileRule.ofPattern("*.tmp");                                  // 任意 .tmp
 * FileRule.ofPattern("logs/&#42;&#42;");                          // logs 目录及其全部子目录
 * FileRule.ofSize(0, 1024 * 1024);                              // 1MB 以内
 * FileRule.modifiedAfter(System.currentTimeMillis() - 86400000L); // 最近一天改过的
 * FileRule.builder().pattern("*.log").minSize(1024).build();     // 组合条件
 * </pre>
 *
 * 说明：规则判断需要"大小"和"修改时间"，而删除事件、目录事件本身没有这两个值，也没有在协议里传输对端文件的修改时间，
 * 所以带大小/日期条件的规则对删除事件、以及"接收方向"的事件不成立；想在所有方向都生效请用纯文件名规则。
 */
public class FileRule {

    /** 未设置的大小/时间条件 */
    public static final long UNSET = -1L;

    private static final String REGEX_PREFIX = "regex:";

    private final String expression;
    private final Pattern pattern;
    private final long minSize;
    private final long maxSize;
    private final long modifiedFrom;
    private final long modifiedTo;

    private FileRule(Builder builder) {
        super();
        this.expression = builder.expression;
        this.minSize = builder.minSize;
        this.maxSize = builder.maxSize;
        this.modifiedFrom = builder.modifiedFrom;
        this.modifiedTo = builder.modifiedTo;
        this.pattern = null == builder.expression ? null : compile(builder.expression, builder.regex, builder.ignoreCase);
    }

    private static Pattern compile(String expression, boolean regex, boolean ignoreCase) {
        String source = expression;
        boolean asRegex = regex;
        if (source.startsWith(REGEX_PREFIX)) {
            source = source.substring(REGEX_PREFIX.length());
            asRegex = true;
        }
        String regexSource = asRegex ? source : globToRegex(source);
        int flags = ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
        return Pattern.compile(regexSource, flags);
    }

    /**
     * 通配符转正则：{@code **} 跨目录，{@code *} 不跨目录，{@code ?} 单个字符。
     *
     * @param glob
     * @return
     */
    static String globToRegex(String glob) {
        StringBuilder out = new StringBuilder("^");
        int index = 0;
        int length = glob.length();
        while (index < length) {
            char c = glob.charAt(index);
            if ('*' == c) {
                if (index + 1 < length && '*' == glob.charAt(index + 1)) {
                    index += 2;
                    if (index < length && '/' == glob.charAt(index)) {
                        // **/ 表示"零到多级目录"，这样 **/*.txt 也能匹配根目录下的 x.txt
                        out.append("(?:[^/]+/)*");
                        index++;
                    } else {
                        out.append(".*");
                    }
                } else {
                    out.append("[^/]*");
                    index++;
                }
            } else if ('?' == c) {
                out.append("[^/]");
                index++;
            } else if ('/' == c) {
                out.append('/');
                index++;
            } else {
                appendRegexChar(out, c);
                index++;
            }
        }
        return out.append('$').toString();
    }

    private static void appendRegexChar(StringBuilder out, char c) {
        switch (c) {
        case '.':
        case '\\':
        case '+':
        case '(':
        case ')':
        case '[':
        case ']':
        case '{':
        case '}':
        case '^':
        case '$':
        case '|':
            out.append('\\').append(c);
            break;
        default:
            out.append(c);
            break;
        }
    }

    /**
     * 统一分隔符，并去掉开头的 {@code ./}
     *
     * @param filePath
     * @return
     */
    static String normalize(String filePath) {
        if (null == filePath) {
            return "";
        }
        String result = filePath.replace('\\', '/');
        while (result.startsWith("./")) {
            result = result.substring(2);
        }
        return result;
    }

    /**
     * 判断文件是否命中这条规则。
     *
     * @param filePath
     *            相对路径
     * @param size
     *            文件大小，{@link #UNSET} 表示未知（未知时带大小条件的规则不命中）
     * @param lastModified
     *            最后修改时间，{@link #UNSET} 表示未知
     * @return
     */
    public boolean matches(String filePath, long size, long lastModified) {
        if (null != pattern && !pattern.matcher(normalize(filePath)).matches()) {
            return false;
        }
        if (UNSET != minSize && (UNSET == size || minSize > size)) {
            return false;
        }
        if (UNSET != maxSize && (UNSET == size || maxSize < size)) {
            return false;
        }
        if (UNSET != modifiedFrom && (UNSET == lastModified || modifiedFrom > lastModified)) {
            return false;
        }
        if (UNSET != modifiedTo && (UNSET == lastModified || modifiedTo < lastModified)) {
            return false;
        }
        return true;
    }

    /**
     * @return 规则的可读描述，用于日志
     */
    public String describe() {
        StringBuilder builder = new StringBuilder(null == expression ? "*" : expression);
        if (UNSET != minSize) {
            builder.append(" size>=").append(minSize);
        }
        if (UNSET != maxSize) {
            builder.append(" size<=").append(maxSize);
        }
        if (UNSET != modifiedFrom) {
            builder.append(" mtime>=").append(formatTime(modifiedFrom));
        }
        if (UNSET != modifiedTo) {
            builder.append(" mtime<=").append(formatTime(modifiedTo));
        }
        return builder.toString();
    }

    /**
     * 每次新建格式器：{@code SimpleDateFormat} 不是线程安全的，不能共享
     *
     * @param time
     * @return
     */
    private static String formatTime(long time) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(time));
    }

    @Override
    public String toString() {
        return describe();
    }

    /**
     * @return 路径表达式
     */
    public String getExpression() {
        return expression;
    }

    /**
     * @return 最低大小（{@link #UNSET} 表示不限制）
     */
    public long getMinSize() {
        return minSize;
    }

    /**
     * @return 最大大小（{@link #UNSET} 表示不限制）
     */
    public long getMaxSize() {
        return maxSize;
    }

    /**
     * @return 修改时间下限（{@link #UNSET} 表示不限制）
     */
    public long getModifiedFrom() {
        return modifiedFrom;
    }

    /**
     * @return 修改时间上限（{@link #UNSET} 表示不限制）
     */
    public long getModifiedTo() {
        return modifiedTo;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 只按路径表达式匹配（通配符写法）
     *
     * @param glob
     * @return
     */
    public static FileRule ofPattern(String glob) {
        return builder().pattern(glob).build();
    }

    /**
     * 只按路径表达式匹配（正则写法）
     *
     * @param regex
     * @return
     */
    public static FileRule ofRegex(String regex) {
        return builder().regex(regex).build();
    }

    /**
     * 只按大小范围匹配（含边界）
     *
     * @param min
     * @param max
     * @return
     */
    public static FileRule ofSize(long min, long max) {
        return builder().minSize(min).maxSize(max).build();
    }

    /**
     * 只按修改时间范围匹配（含边界）
     *
     * @param from
     * @param to
     * @return
     */
    public static FileRule ofModified(long from, long to) {
        return builder().modifiedFrom(from).modifiedTo(to).build();
    }

    /**
     * @param min
     * @return 不低于该大小的文件
     */
    public static FileRule minSize(long min) {
        return builder().minSize(min).build();
    }

    /**
     * @param max
     * @return 不超过该大小的文件
     */
    public static FileRule maxSize(long max) {
        return builder().maxSize(max).build();
    }

    /**
     * @param from
     * @return 修改时间不早于该时间的文件
     */
    public static FileRule modifiedAfter(long from) {
        return builder().modifiedFrom(from).build();
    }

    /**
     * @param to
     * @return 修改时间不晚于该时间的文件
     */
    public static FileRule modifiedBefore(long to) {
        return builder().modifiedTo(to).build();
    }

    /**
     * 规则构造器：把路径表达式与大小/时间条件组合成一条规则
     */
    public static class Builder {
        private String expression;
        private boolean regex;
        /** 默认忽略大小写（Windows 习惯）；需要严格区分时显式关掉 */
        private boolean ignoreCase = true;
        private long minSize = UNSET;
        private long maxSize = UNSET;
        private long modifiedFrom = UNSET;
        private long modifiedTo = UNSET;

        /**
         * @param glob
         *            通配符表达式（{@code **} 跨目录）
         * @return
         */
        public Builder pattern(String glob) {
            this.expression = glob;
            this.regex = false;
            return this;
        }

        /**
         * @param regex
         *            正则表达式（也可以直接写成 {@code regex:xxx} 传给 {@link #pattern(String)}）
         * @return
         */
        public Builder regex(String regex) {
            this.expression = regex;
            this.regex = true;
            return this;
        }

        /**
         * @param ignoreCase
         *            是否忽略大小写
         * @return
         */
        public Builder ignoreCase(boolean ignoreCase) {
            this.ignoreCase = ignoreCase;
            return this;
        }

        /**
         * @param min
         *            最小字节数（含）
         * @return
         */
        public Builder minSize(long min) {
            this.minSize = min;
            return this;
        }

        /**
         * @param max
         *            最大字节数（含）
         * @return
         */
        public Builder maxSize(long max) {
            this.maxSize = max;
            return this;
        }

        /**
         * @param from
         *            修改时间下限（毫秒，含）
         * @return
         */
        public Builder modifiedFrom(long from) {
            this.modifiedFrom = from;
            return this;
        }

        /**
         * @param to
         *            修改时间上限（毫秒，含）
         * @return
         */
        public Builder modifiedTo(long to) {
            this.modifiedTo = to;
            return this;
        }

        /**
         * @return 规则实例
         */
        public FileRule build() {
            return new FileRule(this);
        }
    }
}
