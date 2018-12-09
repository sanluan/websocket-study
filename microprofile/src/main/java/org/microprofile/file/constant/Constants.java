package org.microprofile.file.constant;

import java.nio.charset.Charset;

public class Constants {
    /**
     */
    public final static int DEFULT_BLOCK_SIZE = 1024 * 10;
    /**
     */
    public final static String DEFAULT_CHARSET_NAME ="utf-8";
    /**
     */
    public final static Charset DEFAULT_CHARSET = Charset.forName(DEFAULT_CHARSET_NAME);

}
