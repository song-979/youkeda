package com.youkeda.project.wechatproject.bot.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** JSONL transcript store. Each tool-loop session is isolated in its own file. */
public class FileToolTranscriptStore implements ToolTranscriptStore {

    private static final Logger log = LoggerFactory.getLogger(FileToolTranscriptStore.class);

    private final Path root;
    private final ObjectMapper objectMapper;
    private final int retentionDays;
    private Instant lastCleanup = Instant.EPOCH;

    public FileToolTranscriptStore(Path root) {
        this(root, new ObjectMapper(), 7);
    }

    FileToolTranscriptStore(Path root, ObjectMapper objectMapper) {
        this(root, objectMapper, 7);
    }

    public FileToolTranscriptStore(Path root, int retentionDays) {
        this(root, new ObjectMapper(), retentionDays);
    }

    FileToolTranscriptStore(Path root, ObjectMapper objectMapper, int retentionDays) {
        this.root = root.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.retentionDays = Math.max(1, retentionDays);
        try {
            cleanupExpired(Instant.now().minus(this.retentionDays, ChronoUnit.DAYS));
        } catch (IOException e) {
            log.warn("failed to clean expired tool transcripts at startup: {}", e.getMessage());
        }
    }

    @Override
    public synchronized String append(ToolTranscriptEntry entry) throws IOException {
        cleanupIfDue();
        Files.createDirectories(root);
        Path target = root.resolve(safeSessionId(entry.sessionId()) + ".jsonl").normalize();
        if (!target.startsWith(root)) {
            throw new IOException("tool transcript path escaped its configured root");
        }

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("timestamp", Instant.now().toString());
        record.put("sessionId", entry.sessionId());
        record.put("modelRound", entry.modelRound());
        record.put("toolCallId", entry.toolCallId());
        record.put("toolName", entry.toolName());
        record.put("tokenCount", com.youkeda.project.wechatproject.bot.tool.TokenBudgetUtil
                .countTokens(entry.responseData()));
        record.put("responseData", entry.responseData());
        Files.writeString(target, objectMapper.writeValueAsString(record) + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return "tool-transcript://" + entry.sessionId() + "/" + entry.toolCallId();
    }

    @Override
    public synchronized Optional<ToolTranscriptEntry> read(String reference) throws IOException {
        TranscriptReference parsed = parseReference(reference);
        Path target = root.resolve(safeSessionId(parsed.sessionId()) + ".jsonl").normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try (var lines = Files.lines(target, StandardCharsets.UTF_8)) {
            return lines.map(this::parseEntry)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .filter(entry -> parsed.toolCallId().equals(entry.toolCallId()))
                    .findFirst();
        }
    }

    @Override
    public synchronized List<ToolTranscriptSummary> list(String sessionId, String query, int limit) throws IOException {
        String safeSession = safeSessionId(sessionId);
        Path target = root.resolve(safeSession + ".jsonl").normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            return List.of();
        }
        String normalizedQuery = query != null ? query.strip().toLowerCase(Locale.ROOT) : "";
        int effectiveLimit = Math.max(1, Math.min(50, limit));
        List<ToolTranscriptSummary> summaries = new ArrayList<>();
        try (Stream<String> lines = Files.lines(target, StandardCharsets.UTF_8)) {
            for (String line : lines.toList()) {
                Optional<ToolTranscriptEntry> parsed = parseEntry(line);
                if (parsed.isEmpty()) {
                    continue;
                }
                ToolTranscriptEntry entry = parsed.get();
                String searchable = (entry.toolName() + "\n" + entry.responseData()).toLowerCase(Locale.ROOT);
                if (!normalizedQuery.isEmpty() && !searchable.contains(normalizedQuery)) {
                    continue;
                }
                String reference = "tool-transcript://" + entry.sessionId() + "/" + entry.toolCallId();
                summaries.add(new ToolTranscriptSummary(
                        reference,
                        entry.modelRound(),
                        entry.toolName(),
                        com.youkeda.project.wechatproject.bot.tool.TokenBudgetUtil
                                .countTokens(entry.responseData()),
                        preview(entry.responseData(), 240)));
                if (summaries.size() >= effectiveLimit) {
                    break;
                }
            }
        }
        return List.copyOf(summaries);
    }

    @Override
    public synchronized int cleanupExpired(Instant cutoff) throws IOException {
        if (cutoff == null || !Files.isDirectory(root)) {
            return 0;
        }
        int removed = 0;
        try (Stream<Path> files = Files.list(root)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".jsonl")).toList()) {
                FileTime modified = Files.getLastModifiedTime(file);
                if (modified.toInstant().isBefore(cutoff) && Files.deleteIfExists(file)) {
                    removed++;
                }
            }
        }
        lastCleanup = Instant.now();
        return removed;
    }

    private static String safeSessionId(String sessionId) {
        String safe = sessionId != null ? sessionId.replaceAll("[^A-Za-z0-9._-]", "_") : "unknown";
        return safe.isBlank() ? "unknown" : safe;
    }

    private Optional<ToolTranscriptEntry> parseEntry(String line) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> record = objectMapper.readValue(line, Map.class);
            return Optional.of(new ToolTranscriptEntry(
                    String.valueOf(record.get("sessionId")),
                    ((Number) record.getOrDefault("modelRound", 0)).intValue(),
                    String.valueOf(record.get("toolCallId")),
                    String.valueOf(record.get("toolName")),
                    String.valueOf(record.get("responseData"))));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private void cleanupIfDue() throws IOException {
        Instant now = Instant.now();
        if (lastCleanup.plus(1, ChronoUnit.HOURS).isAfter(now)) {
            return;
        }
        cleanupExpired(now.minus(retentionDays, ChronoUnit.DAYS));
    }

    private static String preview(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= maxChars
                ? normalized : normalized.substring(0, maxChars) + "...";
    }

    private static TranscriptReference parseReference(String reference) throws IOException {
        String prefix = "tool-transcript://";
        if (reference == null || !reference.startsWith(prefix)) {
            throw new IOException("invalid tool transcript reference");
        }
        String value = reference.substring(prefix.length());
        int separator = value.indexOf('/');
        if (separator <= 0 || separator == value.length() - 1) {
            throw new IOException("invalid tool transcript reference");
        }
        return new TranscriptReference(value.substring(0, separator), value.substring(separator + 1));
    }

    private record TranscriptReference(String sessionId, String toolCallId) {
    }
}
