package com.youkeda.project.wechatproject.bot.tool;

/** Extracts the first complete JSON object from model output without breaking quoted braces. */
public final class JsonExtractUtil {

    private JsonExtractUtil() {
    }

    public static String extractJsonObject(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        int start = -1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }

            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                if (start < 0) {
                    start = index;
                }
                depth++;
            } else if (current == '}' && start >= 0 && --depth == 0) {
                return text.substring(start, index + 1);
            }
        }
        return "";
    }
}
