package com.youkeda.project.wechatproject.bot.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

/** String-aware extraction of the first complete JSON object from LLM output. */
public final class JsonExtractUtil {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private JsonExtractUtil() {
    }

    public static String extractJsonObject(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String candidate = stripCodeFences(text.trim());
        for (int start = candidate.indexOf('{'); start >= 0;
             start = candidate.indexOf('{', start + 1)) {
            String extracted = scanObject(candidate, start);
            if (extracted != null && isJsonObject(extracted)) {
                return extracted;
            }
        }
        return null;
    }

    private static boolean isJsonObject(String candidate) {
        try {
            return OBJECT_MAPPER.readTree(candidate).isObject();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String scanObject(String candidate, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return candidate.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static String stripCodeFences(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int newline = text.indexOf('\n');
        if (newline < 0) {
            return text;
        }
        String result = text.substring(newline + 1);
        int closingFence = result.lastIndexOf("```");
        if (closingFence >= 0) {
            result = result.substring(0, closingFence);
        }
        return result.trim();
    }
}
