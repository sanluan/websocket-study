package org.microprofile.file.constant;

import java.nio.charset.Charset;

import com.fasterxml.jackson.databind.ObjectMapper;

public class Constants {
    /**
     */
    public final static String DEFAULT_CHARSET_NAME = "utf-8";
    /**
     */
    public final static Charset DEFAULT_CHARSET = Charset.forName(DEFAULT_CHARSET_NAME);
    /**
     * Json Mapper
     */
    public static final ObjectMapper objectMapper = new ObjectMapper();

}
