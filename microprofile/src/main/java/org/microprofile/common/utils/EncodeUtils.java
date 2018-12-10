package org.microprofile.common.utils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * EncodeUtils
 * 
 */
public class EncodeUtils {
    private static final String CODES = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=";

    /**
     * @param input
     * @return
     */
    public static byte[] base64Decode(String input) {
        if (0 != input.length() % 4) {
            throw new IllegalArgumentException("Invalid base64 input");
        }
        byte decoded[] = new byte[((input.length() * 3) / 4)
                - (input.indexOf('=') > 0 ? (input.length() - input.indexOf('=')) : 0)];
        char[] inChars = input.toCharArray();
        int j = 0;
        int b[] = new int[4];
        for (int i = 0; i < inChars.length; i += 4) {
            b[0] = CODES.indexOf(inChars[i]);
            b[1] = CODES.indexOf(inChars[i + 1]);
            b[2] = CODES.indexOf(inChars[i + 2]);
            b[3] = CODES.indexOf(inChars[i + 3]);
            decoded[j++] = (byte) ((b[0] << 2) | (b[1] >> 4));
            if (b[2] < 64) {
                decoded[j++] = (byte) ((b[1] << 4) | (b[2] >> 2));
                if (b[3] < 64) {
                    decoded[j++] = (byte) ((b[2] << 6) | b[3]);
                }
            }
        }
        return decoded;
    }

    /**
     * @param in
     * @return
     */
    public static String base64Encode(byte[] in) {
        StringBuilder out = new StringBuilder((in.length * 4) / 3);
        int b;
        for (int i = 0; i < in.length; i += 3) {
            b = (in[i] & 0xFC) >> 2;
            out.append(CODES.charAt(b));
            b = (in[i] & 0x03) << 4;
            if (i + 1 < in.length) {
                b |= (in[i + 1] & 0xF0) >> 4;
                out.append(CODES.charAt(b));
                b = (in[i + 1] & 0x0F) << 2;
                if (i + 2 < in.length) {
                    b |= (in[i + 2] & 0xC0) >> 6;
                    out.append(CODES.charAt(b));
                    b = in[i + 2] & 0x3F;
                    out.append(CODES.charAt(b));
                } else {
                    out.append(CODES.charAt(b));
                    out.append('=');
                }
            } else {
                out.append(CODES.charAt(b));
                out.append("==");
            }
        }
        return out.toString();
    }

    /**
     * @param buffer
     * @return hex
     */
    public static String bypeToHex(byte buffer[]) {
        StringBuilder sb = new StringBuilder(buffer.length * 2);
        for (int i = 0; i < buffer.length; i++) {
            sb.append(Character.forDigit((buffer[i] & 240) >> 4, 16));
            sb.append(Character.forDigit(buffer[i] & 15, 16));
        }
        return sb.toString();
    }

    /**
     * @param input
     * @return byte array
     */
    public static byte[] short2Byte(short input) {
        return new byte[] { (byte) (input >> 8), (byte) (input & 0xff) };
    }

    /**
     * @param input
     * @return short value
     */
    public static short bype2Short(byte[] input) {
        if (2 != input.length) {
            throw new IllegalArgumentException("Invalid input");
        }
        return (short) ((input[0] << 8) | (input[1] & 0xff));
    }

    /**
     * @param input
     * @return byte array
     */
    public static byte[] int2Byte(int input) {
        return new byte[] { (byte) (input >> 24), (byte) (input >> 16 & 0xff), (byte) (input >> 8 & 0xff),
                (byte) (input & 0xff) };
    }

    /**
     * @param input
     * @return int value
     */
    public static int bype2Int(byte[] input) {
        if (4 != input.length) {
            throw new IllegalArgumentException("Invalid input");
        }
        return ((input[0]) << 24) | ((input[1] & 0xff) << 16) | ((input[2] & 0xff) << 8) | (input[3] & 0xff);
    }

    /**
     * @param input
     * @return byte array
     */
    public static byte[] long2Byte(long input) {
        return new byte[] { (byte) (input >> 56), (byte) (input >> 48 & 0xff), (byte) (input >> 40 & 0xff),
                (byte) (input >> 32 & 0xff), (byte) (input >> 24 & 0xff), (byte) (input >> 16 & 0xff), (byte) (input >> 8 & 0xff),
                (byte) (input & 0xff) };
    }

    /**
     * @param input
     * @return long value
     */
    public static long bype2Long(byte[] input) {
        if (8 != input.length) {
            throw new IllegalArgumentException("Invalid input");
        }
        return ((long) input[0] << 56) | ((long) (input[1] & 0xff) << 48) | ((long) (input[2] & 0xff) << 40)
                | ((long) (input[3] & 0xff) << 32) | ((long) (input[4] & 0xff) << 24) | ((long) (input[5] & 0xff) << 16)
                | ((long) (input[6] & 0xff) << 8) | (long) (input[7] & 0xff);
    }

    /**
     * @param input
     * @return
     */
    public static byte[] md5(byte[] input) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            messageDigest.update(input);
            return messageDigest.digest();
        } catch (NoSuchAlgorithmException e) {
            return input;
        }
    }

    /**
     * @param input
     * @return
     */
    public static byte[] md2(byte[] input) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD2");
            messageDigest.update(input);
            return messageDigest.digest();
        } catch (NoSuchAlgorithmException e) {
            return input;
        }
    }

    /**
     * @param input
     * @return
     */
    public static byte[] md4(byte[] input) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD4");
            messageDigest.update(input);
            return messageDigest.digest();
        } catch (NoSuchAlgorithmException e) {
            return input;
        }
    }
}
