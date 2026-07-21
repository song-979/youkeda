package com.youkeda.project.wechatproject.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 API 客户端，覆盖 DeepSeek / OpenAI / Ollama 等。
 * 通过配置 {@code agent.ai.api-url} 切换目标服务。
 * <p>
 * 有图片时自动构建多模态请求（OpenAI Vision 格式），
 * 模型不支持时 API 会返回错误，异常信息通过日志输出。
 */
public class OpenAiCompatibleClient implements AiModelClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AgentProperties props;
    private final RestTemplate restTemplate;

    public OpenAiCompatibleClient(AgentProperties props) {
        this.props = props;
        this.restTemplate = createRestTemplate(props);
    }

    private static RestTemplate createRestTemplate(AgentProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeoutMs());
        factory.setReadTimeout(props.getReadTimeoutMs());
        return new RestTemplate(factory);
    }

    @Override
    public String chat(String userMessage, List<String> imageBase64Urls, List<ChatRequest.Message> history) throws IOException {
        ChatRequest request = buildRequest(userMessage, imageBase64Urls, history, false);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(props.getApiKey());

        HttpEntity<ChatRequest> entity = new HttpEntity<>(request, headers);

        try {
            ResponseEntity<ChatResponse> response = restTemplate.postForEntity(
                    props.getApiUrl(), entity, ChatResponse.class);

            ChatResponse body = response.getBody();
            if (body == null) {
                log.warn("empty response body from AI API");
                return "抱歉，AI 服务返回为空，请稍后再试。";
            }

            String content = body.extractContent();
            if (content == null || content.isEmpty()) {
                log.warn("no content in AI response, choices={}", body.getChoices());
                return "抱歉，AI 未生成有效回复，请稍后再试。";
            }

            return content;

        } catch (RestClientException e) {
            String errorMsg = e.getMessage();
            log.error("AI API call failed: url={}, model={}, error={}", props.getApiUrl(), props.getModel(), errorMsg);

            boolean hasImages = imageBase64Urls != null && !imageBase64Urls.isEmpty();
            if (hasImages && (errorMsg != null && (errorMsg.contains("image") || errorMsg.contains("vision") || errorMsg.contains("multimodal") || errorMsg.contains("400") || errorMsg.contains("not supported")))) {
                throw new IOException("该模型不支持图片输入，请切换到多模态模型（如 qwen-vl）。原始错误: " + errorMsg, e);
            }
            throw new IOException("AI service unavailable: " + errorMsg, e);
        }
    }

    @Override
    public String chatStream(String userMessage, List<String> imageBase64Urls,
                             List<ChatRequest.Message> history) throws IOException {
        ChatRequest request = buildRequest(userMessage, imageBase64Urls, history, true);
        String requestJson = OBJECT_MAPPER.writeValueAsString(request);

        HttpURLConnection conn = (HttpURLConnection) URI.create(props.getApiUrl()).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + props.getApiKey());
        conn.setDoOutput(true);
        conn.setConnectTimeout(props.getConnectTimeoutMs());
        conn.setReadTimeout(props.getReadTimeoutMs());

        // 写入请求体
        try (OutputStream os = conn.getOutputStream()) {
            os.write(requestJson.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        int status = conn.getResponseCode();
        if (status != 200) {
            // 读取错误响应体
            try (BufferedReader errReader = new BufferedReader(
                    new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder errBody = new StringBuilder();
                String line;
                while ((line = errReader.readLine()) != null) {
                    errBody.append(line);
                }
                log.error("AI streaming API returned {}: {}", status, errBody);
                throw new IOException("AI service error: HTTP " + status + " - " + errBody);
            }
        }

        // 读取 SSE 流
        StringBuilder fullContent = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                if (!line.startsWith("data: ")) continue;

                String data = line.substring(6); // 去掉 "data: "
                if ("[DONE]".equals(data)) {
                    break;
                }

                // 解析 JSON 提取 delta.content
                String deltaContent = extractDeltaContent(data);
                if (deltaContent != null) {
                    fullContent.append(deltaContent);
                }
            }
        } finally {
            conn.disconnect();
        }

        if (fullContent.isEmpty()) {
            log.warn("no content in AI streaming response");
            return "抱歉，AI 未生成有效回复，请稍后再试。";
        }

        return fullContent.toString();
    }

    private ChatRequest buildRequest(String userMessage, List<String> imageBase64Urls,
                                     List<ChatRequest.Message> history, boolean stream) {
        ChatRequest.Message userMsg;
        if (imageBase64Urls != null && !imageBase64Urls.isEmpty()) {
            log.debug("building multimodal request with {} image(s)", imageBase64Urls.size());
            userMsg = new ChatRequest.Message("user", userMessage, imageBase64Urls);
        } else {
            userMsg = new ChatRequest.Message("user", userMessage);
        }

        // system → history... → current user
        List<ChatRequest.Message> messages = new ArrayList<>();
        messages.add(new ChatRequest.Message("system", props.getSystemPrompt()));
        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
        }
        messages.add(userMsg);

        return new ChatRequest(props.getModel(), messages, props.getTemperature(), props.getMaxTokens(), stream);
    }

    /**
     * 从 SSE data JSON 中提取 delta.content。
     * 流式响应的 JSON 格式：{"choices":[{"delta":{"content":"文本"},"index":0}]}
     * 首个 chunk 的 delta.content 可能为 null（仅含 role），这里做兼容处理。
     */
    @SuppressWarnings("unchecked")
    private static String extractDeltaContent(String dataJson) {
        try {
            Map<String, Object> root = OBJECT_MAPPER.readValue(dataJson, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) root.get("choices");
            if (choices == null || choices.isEmpty()) return null;
            Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
            if (delta == null) return null;
            Object content = delta.get("content");
            return content instanceof String ? (String) content : null;
        } catch (Exception e) {
            log.debug("failed to parse SSE data chunk: {}", dataJson, e);
            return null;
        }
    }
}
