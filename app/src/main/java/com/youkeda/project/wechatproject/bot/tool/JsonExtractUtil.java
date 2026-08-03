package com.youkeda.project.wechatproject.bot.tool;

/**
 * Shared, robust extraction of a JSON object from free-form LLM output.
 * <p>
 * LLMs routinely wrap JSON in prose, markdown code fences, or prefix tags. The naive
 * "first { to last }" approach breaks when the surrounding prose contains braces, and
 * naive brace balancing breaks when JSON string values contain unbalanced braces.
 * This utility handles all three: code fences are stripped first, then a scan finds
 * the first top-level JSON object while respecting string literals and escapes.
 */
public final class JsonExtractUtil {

    private JsonExtractUtil() {
    }

    /**
     * Extract the first complete top-level JSON object ({...}) from the given text.
     *
     * @return the JSON substring including braces, or {@code null} when none is found
     */
    public static String extractJsonObject(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String candidate = stripCodeFences(text.trim());

        int start = candidate.indexOf('{');
        if (start < 0) {
            return null;
        }

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
        // Unbalanced braces — no complete object found.
        return null;
    }

    /**
     * Remove a leading tag such as {@code [FILE:xxx]} that some prompts ask the model
     * to emit before the JSON payload.
     */
    public static String stripLeadingTag(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("[") && trimmed.contains("]") && !trimmed.startsWith("[\"") ) {
            int close = trimmed.indexOf(']');
            String maybeTag = trimmed.substring(0, close + 1);
            if (maybeTag.length() < 200 && !maybeTag.contains("{")) {
                return trimmed.substring(close + 1).trim();
            }
        }
        return trimmed;
    }

    private static String stripCodeFences(String text) {
        String result = text;
        if (result.startsWith("```")) {
            int firstNewline = result.indexOf('\n');
            if (firstNewline > 0) {
                result = result.substring(firstNewline + 1);
            }
            if (result.endsWith("```")) {
                result = result.substring(0, result.length() - 3);
            }
            result = result.trim();
        }
        return result;
    }
}
