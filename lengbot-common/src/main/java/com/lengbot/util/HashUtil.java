package com.lengbot.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 哈希计算工具类。
 */
public final class HashUtil {

    private HashUtil() {
    }

    public static String md5(byte[] data) {
        try {
            return bytesToHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (Exception e) {
            throw new RuntimeException("MD5 计算失败", e);
        }
    }

    public static String sha256(String input) {
        try {
            return bytesToHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 计算失败", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder();
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }
}
