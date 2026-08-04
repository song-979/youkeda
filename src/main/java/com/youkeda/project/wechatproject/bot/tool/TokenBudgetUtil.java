package com.youkeda.project.wechatproject.bot.tool;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

/** Token accounting and boundary-aware truncation for model context assembly. */
public final class TokenBudgetUtil {

    private static final Encoding ENCODING = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    private TokenBudgetUtil() {
    }

    public static int countTokens(String text) {
        return text == null || text.isEmpty() ? 0 : ENCODING.countTokens(text);
    }

    public static String truncateAtBoundary(String text, int maxTokens) {
        if (text == null || text.isEmpty() || countTokens(text) <= maxTokens) {
            return text == null ? "" : text;
        }
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (mid < text.length() && Character.isHighSurrogate(text.charAt(mid - 1))) {
                mid--;
            }
            if (countTokens(text.substring(0, mid)) <= maxTokens) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        int boundary = findBoundary(text, low);
        return text.substring(0, boundary).stripTrailing() + "\n...[context truncated]";
    }

    public static TokenSlice sliceByTokens(String text, int offsetTokens, int maxTokens) {
        String safe = text != null ? text : "";
        int totalTokens = countTokens(safe);
        int offset = Math.max(0, offsetTokens);
        int limit = Math.max(1, maxTokens);
        if (safe.isEmpty() || offset >= totalTokens) {
            return new TokenSlice("", totalTokens, totalTokens, false);
        }

        int start = charIndexAtTokenOffset(safe, offset);
        int low = start;
        int high = safe.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (mid < safe.length() && Character.isHighSurrogate(safe.charAt(mid - 1))) {
                mid--;
            }
            if (countTokens(safe.substring(start, mid)) <= limit) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        String content = safe.substring(start, low);
        int consumed = countTokens(content);
        int nextOffset = Math.min(totalTokens, offset + Math.max(1, consumed));
        return new TokenSlice(content, nextOffset, totalTokens, nextOffset < totalTokens);
    }

    private static int charIndexAtTokenOffset(String text, int offsetTokens) {
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (mid < text.length() && Character.isHighSurrogate(text.charAt(mid - 1))) {
                mid--;
            }
            if (countTokens(text.substring(0, mid)) <= offsetTokens) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    private static int findBoundary(String text, int end) {
        int minimum = Math.max(0, (int) (end * 0.7));
        for (String marker : new String[]{"\n\n", "\n", "。", "！", "？", ". "}) {
            int candidate = text.lastIndexOf(marker, end);
            if (candidate >= minimum) {
                return candidate + marker.length();
            }
        }
        return end;
    }

    public record TokenSlice(String content, int nextOffsetToken, int totalTokens, boolean hasMore) {
    }
}
