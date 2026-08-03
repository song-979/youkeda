package com.youkeda.project.wechatproject.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@RestController
public class SetupController {

    private static final Logger log = LoggerFactory.getLogger(SetupController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path PROPERTIES_PATH = Path.of("src/main/resources/application.properties")
            .toAbsolutePath().normalize();

    private static final Set<String> ALLOWED_TOP_LEVEL_PREFIXES = Set.of("ilink", "agent", "spring.ai");

    private static final Pattern SENSITIVE_KEY_PATTERN = Pattern.compile(
            ".*(api[._\\-]?key|apiKey|apikey|api_key|token|secret|password|private[._\\-]?key|privateKey|private_key).*",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern MASKED_VALUE_PATTERN = Pattern.compile("\\*\\*\\*");

    private static final int MASK_PREFIX_LEN = 3;
    private static final int MASK_SUFFIX_LEN = 4;

    private final ConfigurableEnvironment env;

    public SetupController(ConfigurableEnvironment env) {
        this.env = env;
    }

    // ─── GET /setup/config ────────────────────────────────────────────

    @GetMapping("/setup/config")
    public ResponseEntity<?> getConfig(HttpServletRequest request) {
        if (!isLoopback(request)) {
            log.warn("GET /setup/config denied for remote address: {}", request.getRemoteAddr());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "access denied: loopback clients only"));
        }

        ObjectNode root = MAPPER.createObjectNode();
        for (var ps : env.getPropertySources()) {
            if (!(ps instanceof org.springframework.core.env.EnumerablePropertySource<?> eps)) {
                continue;
            }
            for (String key : eps.getPropertyNames()) {
                Object value = eps.getProperty(key);
                if (value == null) {
                    continue;
                }
                setNestedProperty(root, key, value);
            }
        }

        maskSensitiveRecursive(root);
        return ResponseEntity.ok(root);
    }

    // ─── POST /setup/config ───────────────────────────────────────────

    @PostMapping("/setup/config")
    public ResponseEntity<?> postConfig(@RequestBody Map<String, Object> body,
                                        HttpServletRequest request) {
        if (!isLoopback(request)) {
            log.warn("POST /setup/config denied for remote address: {}", request.getRemoteAddr());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "access denied: loopback clients only"));
        }

        List<String> rejected = new ArrayList<>();
        List<String> skippedMasked = new ArrayList<>();
        List<String> updated = new ArrayList<>();

        for (var entry : body.entrySet()) {
            String key = entry.getKey();
            Object rawValue = entry.getValue();
            String value = rawValue != null ? rawValue.toString() : null;

            if (!isAllowedTopLevelKey(key)) {
                rejected.add(key);
                log.warn("POST /setup/config: rejected unknown top-level key: {}", key);
                continue;
            }

            if (value != null && MASKED_VALUE_PATTERN.matcher(value).find()) {
                skippedMasked.add(key);
                log.info("POST /setup/config: skipping masked value for key: {}", key);
                continue;
            }

            updated.add(key);
        }

        if (!rejected.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of(
                            "error", "unknown top-level keys rejected",
                            "rejectedKeys", rejected));
        }

        try {
            writeProperties(updated, body);
        } catch (IOException e) {
            log.error("failed to write application.properties", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "failed to write configuration: " + e.getMessage()));
        }

        log.info("POST /setup/config: updated {} keys, skipped {} masked values",
                updated.size(), skippedMasked.size());
        return ResponseEntity.ok(Map.of(
                "updated", updated,
                "skippedMasked", skippedMasked));
    }

    // ─── Loopback check ───────────────────────────────────────────────

    private boolean isLoopback(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (remoteAddr == null) {
            return false;
        }
        return "127.0.0.1".equals(remoteAddr)
                || "0:0:0:0:0:0:0:1".equals(remoteAddr)
                || "::1".equals(remoteAddr);
    }

    // ─── Top-level key whitelist ──────────────────────────────────────

    private boolean isAllowedTopLevelKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        for (String prefix : ALLOWED_TOP_LEVEL_PREFIXES) {
            if (key.equals(prefix) || key.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    // ─── Nested property tree builder ─────────────────────────────────

    @SuppressWarnings("unchecked")
    private static void setNestedProperty(ObjectNode root, String key, Object value) {
        String[] parts = key.split("\\.");
        ObjectNode current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            JsonNode child = current.get(part);
            if (child == null || !child.isObject()) {
                current.set(part, MAPPER.createObjectNode());
            }
            current = (ObjectNode) current.get(part);
        }
        String leaf = parts[parts.length - 1];
        if (value instanceof Number num) {
            current.put(leaf, num.doubleValue());
        } else if (value instanceof Boolean bool) {
            current.put(leaf, bool);
        } else {
            current.put(leaf, value.toString());
        }
    }

    // ─── Recursive sensitive-key masking ──────────────────────────────

    private static void maskSensitiveRecursive(JsonNode node) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Iterator<String> fieldNames = obj.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                JsonNode child = obj.get(fieldName);
                if (child.isObject() || child.isArray()) {
                    maskSensitiveRecursive(child);
                } else if (SENSITIVE_KEY_PATTERN.matcher(fieldName).matches()) {
                    String original = child.asText();
                    if (original != null && !original.isBlank()) {
                        obj.put(fieldName, maskValue(original));
                    }
                }
            }
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (JsonNode item : arr) {
                maskSensitiveRecursive(item);
            }
        }
    }

    private static String maskValue(String value) {
        if (value == null || value.length() <= MASK_PREFIX_LEN + MASK_SUFFIX_LEN) {
            return "***";
        }
        return value.substring(0, MASK_PREFIX_LEN)
                + "***"
                + value.substring(value.length() - MASK_SUFFIX_LEN);
    }

    // ─── Properties file writer ───────────────────────────────────────

    private void writeProperties(List<String> keys, Map<String, Object> body) throws IOException {
        List<String> lines;
        if (Files.exists(PROPERTIES_PATH)) {
            lines = new ArrayList<>(Files.readAllLines(PROPERTIES_PATH));
        } else {
            lines = new ArrayList<>();
        }

        Set<String> writtenKeys = new HashSet<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            int eqIdx = line.indexOf('=');
            if (eqIdx < 0) {
                continue;
            }
            String existingKey = line.substring(0, eqIdx).trim();
            for (String updateKey : keys) {
                if (existingKey.equals(updateKey)) {
                    String newValue = body.get(updateKey).toString();
                    String prefix = line.substring(0, line.indexOf(existingKey));
                    lines.set(i, prefix + updateKey + "=" + newValue);
                    writtenKeys.add(updateKey);
                    break;
                }
            }
        }

        // Append new keys that weren't found
        for (String updateKey : keys) {
            if (!writtenKeys.contains(updateKey)) {
                lines.add(updateKey + "=" + body.get(updateKey).toString());
            }
        }

        Files.write(PROPERTIES_PATH, lines, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
