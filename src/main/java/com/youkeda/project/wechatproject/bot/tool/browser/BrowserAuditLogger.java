package com.youkeda.project.wechatproject.bot.tool.browser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Structured audit logging for all browser operations.
 * Logs to SLF4J logger "browser-audit" and optionally to a dedicated file.
 */
public class BrowserAuditLogger {

    private static final Logger log = LoggerFactory.getLogger("browser-audit");
    private static final DateTimeFormatter ISO_FORMAT = DateTimeFormatter.ISO_INSTANT;

    private final BrowserMcpProperties properties;
    private Path auditFilePath;

    public BrowserAuditLogger(BrowserMcpProperties properties) {
        this.properties = properties;
        String auditPath = "data/browser-audit/audit.log";
        if (auditPath != null && !auditPath.isBlank()) {
            this.auditFilePath = Path.of(auditPath);
            try {
                Files.createDirectories(this.auditFilePath.getParent());
            } catch (IOException e) {
                log.warn("Failed to create audit directory: {}", e.getMessage());
            }
        }
    }

    /**
     * Log a browser action.
     */
    public void logAction(String userId, String operation, String url, long durationMs, boolean success, String summary) {
        String entry = String.format("AUDIT | time=%s | userId=%s | op=%s | url=%s | durationMs=%d | success=%s | summary=%s",
                ISO_FORMAT.format(Instant.now()), userId, operation, url, durationMs, success, summary);
        log.info("{}", entry);
        appendToFile(entry);
    }

    /**
     * Log a security block event.
     */
    public void logSecurityBlock(String userId, String reason, String detail) {
        String entry = String.format("SECURITY_BLOCK | time=%s | userId=%s | reason=%s | detail=%s",
                ISO_FORMAT.format(Instant.now()), userId, reason, detail);
        log.warn("{}", entry);
        appendToFile(entry);
    }

    /**
     * Log an exception during browser operations.
     */
    public void logError(String userId, String operation, String url, Throwable error) {
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        String entry = String.format("ERROR | time=%s | userId=%s | op=%s | url=%s | error=%s | trace=%s",
                ISO_FORMAT.format(Instant.now()), userId, operation, url, error.getMessage(), sw.toString());
        log.error("{}", entry);
        appendToFile(entry);
    }

    private void appendToFile(String entry) {
        if (auditFilePath == null) return;
        try {
            Files.writeString(auditFilePath, entry + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.debug("Failed to write audit entry to file: {}", e.getMessage());
        }
    }
}
