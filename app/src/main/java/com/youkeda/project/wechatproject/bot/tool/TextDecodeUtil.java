package com.youkeda.project.wechatproject.bot.tool;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public final class TextDecodeUtil {

    private TextDecodeUtil() {
    }

    public static String decode(byte[] bytes) {
        try {
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (!looksCorrupted(text)) {
                return text;
            }
        } catch (Exception ignored) {
        }

        try {
            return new String(bytes, Charset.forName("GBK"));
        } catch (Exception e) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static boolean looksCorrupted(String text) {
        if (text.length() < 50) {
            return false;
        }
        int replacementCount = 0;
        for (int i = 0; i < Math.min(text.length(), 200); i++) {
            if (text.charAt(i) == '\uFFFD') {
                replacementCount++;
            }
        }
        return replacementCount > 3;
    }
}
