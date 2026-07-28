package com.youkeda.project.wechatproject.bot.tool;

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

/**
 * Low-level HTTP client for DiDi MCP Server (JSON-RPC 2.0 over Streamable HTTP).
 */
public class DiDiMcpClient {

    private static final Logger log = LoggerFactory.getLogger(DiDiMcpClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public DiDiMcpClient(String baseUrl) {
        this.restTemplate = createRestTemplate();
        this.baseUrl = baseUrl;
        log.info("DiDi MCP client initialized, endpoint: {}", baseUrl);
    }

    private static RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        return new RestTemplate(factory);
    }

    /**
     * Call a DiDi MCP tool by name with the given arguments.
     *
     * @param toolName  MCP tool name (e.g. "taxi_estimate")
     * @param arguments JSON object with tool parameters
     * @return the "result" node from the JSON-RPC response
     */
    public JsonNode callTool(String toolName, JsonNode arguments) {
        ObjectNode requestBody = OBJECT_MAPPER.createObjectNode();
        requestBody.put("jsonrpc", "2.0");
        requestBody.put("method", "tools/call");
        requestBody.put("id", 1);

        ObjectNode params = OBJECT_MAPPER.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments);
        requestBody.set("params", params);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/json; charset=utf-8"));

        HttpEntity<String> entity = new HttpEntity<>(requestBody.toString(), headers);
        log.info("DiDi MCP call: {} with args: {}", toolName, arguments);

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl, HttpMethod.POST, entity, String.class);

        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(response.getBody());
        } catch (Exception e) {
            throw new DiDiMcpException("-1", "Failed to parse response: " + e.getMessage());
        }

        if (root.has("error")) {
            JsonNode error = root.get("error");
            String code = error.path("code").asText();
            String message = error.path("message").asText("");
            throw new DiDiMcpException(code, message);
        }

        return root.path("result");
    }

    /**
     * Extract human-readable text from a tool result's content[0].text.
     */
    public String getTextContent(JsonNode result) {
        JsonNode content = result.path("content");
        if (content.isArray() && content.size() > 0) {
            JsonNode text = content.get(0).path("text");
            return text.isMissingNode() ? "" : text.asText();
        }
        return "";
    }

    /**
     * Extract structured data from a tool result (taxi tools only).
     */
    public JsonNode getStructuredContent(JsonNode result) {
        return result.path("structuredContent");
    }

    /**
     * DiDi MCP exception with error code.
     */
    public static class DiDiMcpException extends RuntimeException {

        private final String code;

        public DiDiMcpException(String code, String message) {
            super("DiDi error [" + code + "]: " + message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }

        /** The traceId/estimate has expired and needs to be re-estimated. */
        public boolean isExpired() {
            return "-32021".equals(code);
        }
    }
}
