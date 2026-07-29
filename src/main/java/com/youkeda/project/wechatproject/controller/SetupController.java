package com.youkeda.project.wechatproject.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.youkeda.project.wechatproject.bot.service.BotService.MessageBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@RestController
@ConditionalOnProperty(prefix = "ilink", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SetupController {

    private static final Logger log = LoggerFactory.getLogger(SetupController.class);
    private static final Path LOCAL_CONFIG_PATH = Path.of("config", "application-local.yaml");
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final ILinkClient ilinkClient;
    private final MessageBridge messageBridge;

    public SetupController(ILinkClient ilinkClient, MessageBridge messageBridge) {
        this.ilinkClient = ilinkClient;
        this.messageBridge = messageBridge;
    }

    /**
     * Redirect root to setup wizard.
     */
    @GetMapping("/")
    public ResponseEntity<Void> rootRedirect() {
        return ResponseEntity.status(302)
                .header("Location", "/setup")
                .build();
    }

    /**
     * Serve the setup wizard HTML page.
     */
    @GetMapping(value = "/setup", produces = "text/html;charset=UTF-8")
    public String setupPage() throws IOException {
        ClassPathResource resource = new ClassPathResource("static/setup.html");
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * Check current iLink login status.
     * Returns: { "loggedIn": true/false, "botId": "..." }
     */
    @GetMapping("/api/setup/login-status")
    public ResponseEntity<Map<String, Object>> loginStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (ilinkClient.isLoggedIn()) {
            result.put("loggedIn", true);
            result.put("botId", ilinkClient.getLoginContext().getBotId());
            result.put("userId", ilinkClient.getLoginContext().getUserId());
        } else {
            result.put("loggedIn", false);
            String qrcode = messageBridge.getQrcode();
            result.put("hasQrcode", qrcode != null && !qrcode.isEmpty());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Get QR code content as JSON (for the frontend to render).
     */
    @GetMapping("/api/setup/qrcode-content")
    public ResponseEntity<Map<String, Object>> qrcodeContent() {
        Map<String, Object> result = new LinkedHashMap<>();
        String qrcode = messageBridge.getQrcode();
        if (qrcode == null || qrcode.isEmpty()) {
            result.put("available", false);
            result.put("message", "QR code not ready yet, waiting for iLink SDK...");
        } else {
            result.put("available", true);
            result.put("content", qrcode);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Get current effective configuration.
     * Reads application-local.yaml if exists, otherwise returns empty defaults.
     */
    @GetMapping("/api/setup/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        if (Files.exists(LOCAL_CONFIG_PATH)) {
            try {
                String content = Files.readString(LOCAL_CONFIG_PATH);
                @SuppressWarnings("unchecked")
                Map<String, Object> localConfig = YAML_MAPPER.readValue(content, Map.class);
                if (localConfig != null) {
                    config.putAll(localConfig);
                }
            } catch (Exception e) {
                log.warn("Failed to read local config: {}", e.getMessage());
            }
        }
        return ResponseEntity.ok(config);
    }

    /**
     * Save user configuration to application-local.yaml.
     * Body: JSON object matching the YAML config structure.
     * Example:
     * {
     *   "agent": {
     *     "ai": {
     *       "api-key": "sk-xxx",
     *       "model": "gpt-4o"
     *     }
     *   }
     * }
     */
    @PostMapping(value = "/api/setup/config", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody Map<String, Object> config) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // Ensure directory exists
            Files.createDirectories(LOCAL_CONFIG_PATH.getParent());

            // Read existing config if present, merge with new config
            Map<String, Object> merged = new LinkedHashMap<>();
            if (Files.exists(LOCAL_CONFIG_PATH)) {
                String existing = Files.readString(LOCAL_CONFIG_PATH);
                @SuppressWarnings("unchecked")
                Map<String, Object> existingConfig = YAML_MAPPER.readValue(existing, Map.class);
                if (existingConfig != null) {
                    merged.putAll(existingConfig);
                }
            }
            deepMerge(merged, config);

            // Write as YAML
            StringWriter writer = new StringWriter();
            writer.write("# 微信 AI 助手 - 个人配置文件\n");
            writer.write("# 此文件由配置向导自动生成，也可以手动编辑\n");
            writer.write("# 修改后重启应用生效\n\n");
            YAML_MAPPER.writerWithDefaultPrettyPrinter().writeValue(writer, merged);

            Files.writeString(LOCAL_CONFIG_PATH, writer.toString());
            log.info("Configuration saved to {}", LOCAL_CONFIG_PATH.toAbsolutePath());

            result.put("success", true);
            result.put("path", LOCAL_CONFIG_PATH.toAbsolutePath().toString());
            result.put("message", "配置已保存。请重启应用使配置生效。");
        } catch (Exception e) {
            log.error("Failed to save config", e);
            result.put("success", false);
            result.put("message", "保存失败: " + e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @SuppressWarnings("unchecked")
    private void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map && target.get(key) instanceof Map) {
                deepMerge((Map<String, Object>) target.get(key), (Map<String, Object>) value);
            } else {
                target.put(key, value);
            }
        }
    }
}
