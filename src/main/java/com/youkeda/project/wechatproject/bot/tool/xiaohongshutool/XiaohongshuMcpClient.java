package com.youkeda.project.wechatproject.bot.tool.xiaohongshutool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Low-level JSON-RPC client for a Xiaohongshu MCP server.
 */
public class XiaohongshuMcpClient {

    private static final Logger log = LoggerFactory.getLogger(XiaohongshuMcpClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RestTemplate restTemplate;
    private final String endpoint;

    public XiaohongshuMcpClient(XiaohongshuProperties properties) {
        this(createRestTemplate(properties), properties.getEndpoint());
    }

    public XiaohongshuMcpClient(RestTemplate restTemplate, String endpoint) {
        this.restTemplate = restTemplate;
        this.endpoint = endpoint;
        log.info("Xiaohongshu MCP client initialized, endpoint: {}", endpoint);
    }

    public JsonNode callTool(String toolName, JsonNode arguments) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new XiaohongshuMcpException("-1", "Xiaohongshu MCP endpoint is not configured");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new XiaohongshuMcpException("-1", "Xiaohongshu MCP tool name is blank");
        }

        ObjectNode requestBody = OBJECT_MAPPER.createObjectNode();
        requestBody.put("jsonrpc", "2.0");
        requestBody.put("method", "tools/call");
        requestBody.put("id", 1);

        ObjectNode params = OBJECT_MAPPER.createObjectNode();
        params.put("name", toolName.trim());
        params.set("arguments", arguments != null ? arguments : OBJECT_MAPPER.createObjectNode());
        requestBody.set("params", params);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(requestBody.toString(), headers);

        ResponseEntity<String> response = restTemplate.exchange(endpoint, HttpMethod.POST, entity, String.class);
        String body = response.getBody();
        if (body == null || body.isBlank()) {
            throw new XiaohongshuMcpException("-1", "Xiaohongshu MCP returned an empty response");
        }

        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(body);
        } catch (Exception e) {
            throw new XiaohongshuMcpException("-1", "Failed to parse Xiaohongshu MCP response: " + e.getMessage());
        }

        if (root.has("error")) {
            JsonNode error = root.get("error");
            throw new XiaohongshuMcpException(
                    error.path("code").asText("-1"),
                    error.path("message").asText("unknown Xiaohongshu MCP error"));
        }
        JsonNode result = root.path("result");
        if (result.path("isError").asBoolean(false)) {
            String message = getTextContent(result);
            if (message.isBlank()) {
                message = "Xiaohongshu MCP tool returned an error";
            }
            throw new XiaohongshuMcpException("tool_error", message);
        }
        return result;
    }

    public String getTextContent(JsonNode result) {
        if (result == null || result.isMissingNode()) {
            return "";
        }
        JsonNode content = result.path("content");
        if (!content.isArray()) {
            return "";
        }

        List<String> texts = new ArrayList<>();
        for (JsonNode item : content) {
            String text = item.path("text").asText("");
            if (!text.isBlank()) {
                texts.add(text);
            }
        }
        return String.join("\n", texts).trim();
    }

    public JsonNode getStructuredContent(JsonNode result) {
        return result != null ? result.path("structuredContent") : OBJECT_MAPPER.missingNode();
    }

    public List<McpImageContent> getImageContents(JsonNode result) {
        if (result == null || result.isMissingNode()) {
            return List.of();
        }
        JsonNode content = result.path("content");
        if (!content.isArray()) {
            return List.of();
        }

        List<McpImageContent> images = new ArrayList<>();
        for (JsonNode item : content) {
            if (!"image".equalsIgnoreCase(item.path("type").asText(""))) {
                continue;
            }
            String data = item.path("data").asText("");
            if (data.isBlank()) {
                continue;
            }
            String mimeType = item.path("mimeType").asText("");
            if (mimeType.isBlank()) {
                mimeType = item.path("mime_type").asText("image/png");
            }
            images.add(new McpImageContent(mimeType, data));
        }
        return images;
    }

    public record McpImageContent(String mimeType, String data) {
        public McpImageContent {
            mimeType = mimeType == null || mimeType.isBlank() ? "image/png" : mimeType;
            data = data == null ? "" : data.trim();
        }

        public String toDataUri() {
            return "data:" + mimeType + ";base64," + data;
        }
    }

    private static RestTemplate createRestTemplate(XiaohongshuProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        return new RestTemplate(factory);
    }

    public static class XiaohongshuMcpException extends RuntimeException {

        private final String code;

        public XiaohongshuMcpException(String code, String message) {
            super("Xiaohongshu MCP error [" + code + "]: " + message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }
}
