package com.youkeda.project.wechatproject.bot.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * File-based per-agent persistent memory.
 *
 * <p>Each agent type gets its own JSON file per user:
 * <pre>data/memory/{userId}/agent/{agentType}/memory.json</pre>
 *
 * <p>Stores key-value facts with timestamp metadata. Supports TTL expiration.
 * Falls back to simple keyword matching for search (no embedding dependency).
 */
public class FileBasedAgentMemory implements AgentMemory {

    private static final Logger log = LoggerFactory.getLogger(FileBasedAgentMemory.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String agentType;
    private final Path basePath;
    private final long ttlSeconds;

    public FileBasedAgentMemory(String agentType, String basePath, long ttlSeconds) {
        this.agentType = agentType;
        this.basePath = Path.of(basePath);
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public void remember(String userId, String key, String value) {
        Map<String, FactEntry> facts = loadFacts(userId);
        facts.put(key, new FactEntry(value, Instant.now().toEpochMilli()));
        saveFacts(userId, facts);
        log.debug("[{}] remembered: {}={}", agentType, key, truncate(value, 80));
    }

    @Override
    public Optional<String> recall(String userId, String key) {
        Map<String, FactEntry> facts = loadFacts(userId);
        FactEntry entry = facts.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (isExpired(entry)) {
            facts.remove(key);
            saveFacts(userId, facts);
            return Optional.empty();
        }
        return Optional.of(entry.value);
    }

    @Override
    public List<String> search(String userId, String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Map<String, FactEntry> facts = loadFacts(userId);
        String q = query.toLowerCase();
        List<Map.Entry<String, FactEntry>> scored = facts.entrySet().stream()
                .filter(e -> !isExpired(e.getValue()))
                .sorted((a, b) -> Integer.compare(
                        matchScore(q, b.getKey(), b.getValue().value),
                        matchScore(q, a.getKey(), a.getValue().value)))
                .limit(topK)
                .toList();

        List<String> results = new ArrayList<>();
        for (var e : scored) {
            results.add(e.getKey() + ": " + e.getValue().value);
        }
        return results;
    }

    @Override
    public void forget(String userId, String key) {
        Map<String, FactEntry> facts = loadFacts(userId);
        facts.remove(key);
        saveFacts(userId, facts);
    }

    @Override
    public void clear(String userId) {
        Path file = memoryFile(userId);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Failed to clear agent memory file: {}", file, e);
        }
    }

    /** Build compact context string for injection into agent's system prompt. */
    public String buildContextPrompt(String userId, String taskInstruction) {
        Map<String, FactEntry> facts = loadFacts(userId);
        List<Map.Entry<String, FactEntry>> relevant = facts.entrySet().stream()
                .filter(e -> !isExpired(e.getValue()))
                .toList();

        if (relevant.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n## 历史状态记忆（").append(agentType).append("）\n");
        for (var entry : relevant) {
            sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue().value).append("\n");
        }
        return sb.toString();
    }

    // --- internal ---

    private Path memoryFile(String userId) {
        return basePath.resolve(userId).resolve("agent").resolve(agentType).resolve("memory.json");
    }

    private Map<String, FactEntry> loadFacts(String userId) {
        Path file = memoryFile(userId);
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        try {
            String content = Files.readString(file);
            if (content.isBlank()) {
                return new LinkedHashMap<>();
            }
            Map<String, FactEntry> facts = objectMapper.readValue(content,
                    new TypeReference<LinkedHashMap<String, FactEntry>>() {});
            // Clean expired entries on load
            facts.entrySet().removeIf(e -> isExpired(e.getValue()));
            return facts;
        } catch (IOException e) {
            log.warn("Failed to load agent memory: {}", file, e);
            return new LinkedHashMap<>();
        }
    }

    private void saveFacts(String userId, Map<String, FactEntry> facts) {
        Path file = memoryFile(userId);
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), facts);
        } catch (IOException e) {
            log.warn("Failed to save agent memory: {}", file, e);
        }
    }

    private boolean isExpired(FactEntry entry) {
        if (ttlSeconds <= 0) {
            return false;
        }
        return Instant.now().toEpochMilli() - entry.timestamp > ttlSeconds * 1000;
    }

    private int matchScore(String query, String key, String value) {
        int score = 0;
        if (key.toLowerCase().contains(query)) score += 10;
        if (value.toLowerCase().contains(query)) score += 5;
        for (String word : query.split("\\s+")) {
            if (word.length() >= 2 && key.toLowerCase().contains(word)) score += 3;
            if (word.length() >= 2 && value.toLowerCase().contains(word)) score += 1;
        }
        return score;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    /** Internal storage entry. */
    public record FactEntry(String value, long timestamp) {
        // no-arg for Jackson
        public FactEntry() { this("", 0); }
    }
}
