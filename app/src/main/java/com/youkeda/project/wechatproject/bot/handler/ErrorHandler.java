package com.youkeda.project.wechatproject.bot.handler;

import org.slf4j.Logger;

import java.io.IOException;
import java.time.format.DateTimeParseException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Centralized exception handling for the WeChat bot project.
 *
 * <p>All recurring error-handling patterns from ~100 catch blocks across the
 * codebase are consolidated here:
 *
 * <ul>
 *   <li><b>Service layer</b> — log + wrap as {@link IOException}</li>
 *   <li><b>Tool layer</b> — log + return Chinese error string</li>
 *   <li><b>Best-effort</b> — log + swallow (non-critical paths)</li>
 *   <li><b>InterruptedException</b> — restore flag + wrap as IOException</li>
 *   <li><b>DateTimeParseException</b> — catch common parse failures</li>
 *   <li><b>Resource cleanup</b> — close quietly</li>
 *   <li><b>User-facing messages</b> — format error replies</li>
 *   <li><b>Network detection</b> — check for connectivity failures</li>
 * </ul>
 */
public final class ErrorHandler {

    private ErrorHandler() {
    }

    // ========================================================================
    // Service layer: log + wrap as IOException
    // ========================================================================

    /**
     * Log at ERROR and wrap the exception as {@link IOException}.
     * If already an IOException, return it unchanged.
     */
    public static IOException wrapIo(Logger log, String message, Exception e) {
        log.error(message, e);
        if (e instanceof IOException io) {
            return io;
        }
        return new IOException(message + ": " + e.getMessage(), e);
    }

    /**
     * Log at WARN and wrap as {@link IOException}.
     */
    public static IOException wrapIoWarn(Logger log, String message, Exception e) {
        log.warn(message, e);
        if (e instanceof IOException io) {
            return io;
        }
        return new IOException(message + ": " + e.getMessage(), e);
    }

    /**
     * Log at ERROR and throw a new {@link IOException}.
     * Unlike {@link #wrapIo} this always creates a new IOException even if the
     * original is already one — useful when a different message is needed.
     */
    public static IOException newIo(Logger log, String message, Exception e) {
        log.error(message, e);
        return new IOException(message + ": " + e.getMessage(), e);
    }

    // ========================================================================
    // InterruptedException → IOException
    // ========================================================================

    /**
     * Handle {@link InterruptedException}: restore the interrupt flag, log a
     * warning, and return an {@link IOException} so the caller can propagate it.
     */
    public static IOException interrupted(Logger log, String context, InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("{} was interrupted", context, e);
        return new IOException(context + " interrupted", e);
    }

    // ========================================================================
    // Tool layer: log + return Chinese error string
    // ========================================================================

    /**
     * Log at ERROR and return a user-facing Chinese error string.
     * Pattern: {@code "操作名失败：" + e.getMessage()}.
     */
    public static String toolError(Logger log, String operation, Exception e) {
        log.error("{} failed", operation, e);
        return operation + "失败：" + e.getMessage();
    }

    /**
     * Log at WARN and return a user-facing Chinese error string.
     */
    public static String toolErrorWarn(Logger log, String operation, Exception e) {
        log.warn("{} failed: {}", operation, e.getMessage());
        return operation + "失败：" + e.getMessage();
    }

    // ========================================================================
    // Best-effort: log + swallow
    // ========================================================================

    /**
     * Log at ERROR and swallow the exception — use when the operation is
     * non-critical and there is no meaningful recovery.
     */
    public static void swallow(Logger log, String message, Exception e) {
        log.error(message, e);
    }

    /**
     * Log at WARN and swallow.
     */
    public static void swallowWarn(Logger log, String message, Exception e) {
        log.warn(message, e);
    }

    /**
     * Log at DEBUG and swallow.
     */
    public static void swallowDebug(Logger log, String message, Exception e) {
        log.debug(message, e);
    }

    // ========================================================================
    // Date-time parse
    // ========================================================================

    /**
     * Run a date/time parser, catching {@link DateTimeParseException} and
     * {@link IllegalArgumentException} and delegating to a fallback function.
     */
    public static <T> T parseDateTime(Supplier<T> parser, Function<Exception, T> fallback) {
        try {
            return parser.get();
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return fallback.apply(e);
        }
    }

    // ========================================================================
    // Resource cleanup
    // ========================================================================

    /**
     * Close resources quietly. Failures are logged at DEBUG level and never
     * propagate.
     */
    public static void closeQuietly(Logger log, AutoCloseable... resources) {
        for (AutoCloseable r : resources) {
            if (r == null) {
                continue;
            }
            try {
                r.close();
            } catch (Exception e) {
                log.debug("error closing resource", e);
            }
        }
    }

    // ========================================================================
    // User-facing error messages
    // ========================================================================

    /**
     * Format a user-facing Chinese error reply for AI service failures.
     *
     * @param detail nullable detail message; if null or blank a generic
     *               fallback is returned
     */
    public static String userErrorReply(String detail) {
        if (detail != null && !detail.isBlank()) {
            return "抱歉，AI 服务返回错误：" + detail + "\n请稍后再试。";
        }
        return "抱歉，处理消息时发生错误，请稍后再试。";
    }

    /**
     * Format a user-facing message for unsupported message types.
     */
    public static String notSupportedReply() {
        return "目前支持文本、图片和语音消息，请发文字、图片或语音给我。";
    }

    // ========================================================================
    // Network failure detection
    // ========================================================================

    /**
     * Check whether a throwable indicates a network or connectivity failure.
     */
    public static boolean isNetworkFailure(Throwable t) {
        if (t == null) {
            return false;
        }
        String msg = t.getMessage();
        if (msg == null) {
            return false;
        }
        String lower = msg.toLowerCase();
        return lower.contains("connect") || lower.contains("timeout")
                || lower.contains("unreachable") || lower.contains("network")
                || lower.contains("refused") || lower.contains("reset");
    }

    /**
     * Check whether a throwable indicates an access-denied / permission problem.
     */
    public static boolean isAccessDenied(Throwable t) {
        if (t == null) {
            return false;
        }
        String msg = t.getMessage();
        if (msg == null) {
            return false;
        }
        String lower = msg.toLowerCase();
        return lower.contains("access denied") || lower.contains("permission")
                || lower.contains("forbidden") || lower.contains("unauthorized")
                || lower.contains("not allowed");
    }
}
