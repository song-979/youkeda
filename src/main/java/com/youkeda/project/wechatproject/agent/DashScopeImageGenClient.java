package com.youkeda.project.wechatproject.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通义万相 DashScope 图片生成客户端。
 * <p>
 * 使用异步提交 + 轮询模式：POST 提交任务 → 轮询 task 状态 → 下载图片。
 * 适用于工作空间级别的 API，也兼容标准 DashScope 账号。
 *
 * @see <a href="https://help.aliyun.com/zh/model-studio/qwen-image-api">千问图像API参考</a>
 */
public class DashScopeImageGenClient implements ImageGenClient {

    private static final Logger log = LoggerFactory.getLogger(DashScopeImageGenClient.class);

    static final String DEFAULT_API_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/image-generation/generation";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int POLL_INTERVAL_MS = 2000;
    private static final int MAX_POLL_COUNT = 60;  // 最多等 2 分钟

    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final String size;
    private final int n;
    private final RestTemplate restTemplate;
    private final String tasksBaseUrl;

    public DashScopeImageGenClient(AgentProperties props) {
        this.apiUrl = props.getImageGenApiUrl() != null && !props.getImageGenApiUrl().isEmpty()
                ? props.getImageGenApiUrl() : DEFAULT_API_URL;
        this.apiKey = props.getImageGenApiKey() != null && !props.getImageGenApiKey().isEmpty()
                ? props.getImageGenApiKey() : props.getApiKey();
        this.model = props.getImageGenModel();
        this.size = props.getImageGenSize();
        this.n = props.getImageGenN();
        this.restTemplate = createRestTemplate(props);
        // 从 apiUrl 提取 base，构建任务轮询 URL
        this.tasksBaseUrl = deriveTasksBaseUrl(this.apiUrl);
    }

    private static String deriveTasksBaseUrl(String apiUrl) {
        URI uri = URI.create(apiUrl);
        return uri.getScheme() + "://" + uri.getHost() + "/api/v1/tasks";
    }

    private static RestTemplate createRestTemplate(AgentProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeoutMs());
        factory.setReadTimeout(180_000);
        return new RestTemplate(factory);
    }

    @Override
    public byte[] generate(String prompt) throws IOException {
        // 1. 异步提交任务
        String taskId = submitTask(prompt);
        log.info("image gen task submitted: taskId={}", taskId);

        // 2. 轮询等待完成
        String imageUrl = pollTask(taskId);

        // 3. 下载图片
        log.info("image gen task completed, downloading from: {}", imageUrl);
        return downloadImage(imageUrl);
    }

    /**
     * 异步提交图片生成任务。
     */
    private String submitTask(String prompt) throws IOException {
        Map<String, Object> requestBody = buildRequestBody(prompt);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        headers.set("X-DashScope-Async", "enable");  // 关键：启用异步模式

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<TaskSubmitResponse> response = restTemplate.postForEntity(
                    apiUrl, entity, TaskSubmitResponse.class);

            TaskSubmitResponse body = response.getBody();
            if (body == null || body.output == null || body.output.taskId == null) {
                throw new IOException("task submission returned no task_id");
            }
            return body.output.taskId;

        } catch (RestClientException e) {
            log.error("image gen task submission failed: url={}, model={}, error={}",
                    apiUrl, model, e.getMessage());
            throw new IOException("Image generation task submission failed: " + e.getMessage(), e);
        }
    }

    /**
     * 轮询任务状态直到完成。
     */
    private String pollTask(String taskId) throws IOException {
        String taskUrl = tasksBaseUrl + "/" + taskId;

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);

        for (int i = 0; i < MAX_POLL_COUNT; i++) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Polling interrupted", e);
            }

            try {
                ResponseEntity<String> response = restTemplate.exchange(
                        taskUrl, HttpMethod.GET, new HttpEntity<>(headers), String.class);

                String raw = response.getBody();
                if (raw == null || raw.isEmpty()) continue;

                // 先提取 task_status
                Map<String, Object> root = OBJECT_MAPPER.readValue(raw, Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> output = (Map<String, Object>) root.get("output");
                if (output == null) continue;

                String status = (String) output.get("task_status");
                log.debug("task {} status: {}", taskId, status);

                if ("SUCCEEDED".equals(status)) {
                    String imageUrl = extractImageUrlFromOutput(output);
                    if (imageUrl == null) {
                        log.error("image URL not found in task response: {}", raw);
                        throw new IOException("task succeeded but no image URL found in response");
                    }
                    return imageUrl;
                }

                if ("FAILED".equals(status)) {
                    String msg = (String) output.get("message");
                    throw new IOException("image generation task failed: " + (msg != null ? msg : "unknown"));
                }

            } catch (RestClientException e) {
                log.warn("poll task {} failed (attempt {}): {}", taskId, i + 1, e.getMessage());
            }
        }

        throw new IOException("image generation timed out after " + (MAX_POLL_COUNT * POLL_INTERVAL_MS / 1000) + "s");
    }

    private Map<String, Object> buildRequestBody(String prompt) {
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("text", prompt);

        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", List.of(textPart));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("messages", List.of(userMsg));

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("size", size);
        parameters.put("n", n);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", input);
        body.put("parameters", parameters);
        return body;
    }

    /**
     * 从 task output 中提取图片 URL，兼容多种响应格式。
     */
    @SuppressWarnings("unchecked")
    private static String extractImageUrlFromOutput(Map<String, Object> output) {
        // 格式1：output.choices[0].message.content[0].image（Wan/多模态端点）
        Object choicesObj = output.get("choices");
        if (choicesObj instanceof List<?> choices && !choices.isEmpty()) {
            String url = extractImageFromChoices((List<Object>) choices);
            if (url != null) return url;
        }

        // 格式2：output.results[].url（老式图片端点）
        // 格式3：output.results[].output.choices[0].message.content[0].image
        Object resultsObj = output.get("results");
        if (resultsObj instanceof List<?> results) {
            for (Object r : results) {
                if (!(r instanceof Map<?, ?> result)) continue;

                Object url = result.get("url");
                if (url instanceof String s && !s.isEmpty()) return s;

                Object nested = result.get("output");
                if (nested instanceof Map<?, ?> nestedOutput) {
                    Object innerChoices = nestedOutput.get("choices");
                    if (innerChoices instanceof List<?> cl && !cl.isEmpty()) {
                        String innerUrl = extractImageFromChoices((List<Object>) cl);
                        if (innerUrl != null) return innerUrl;
                    }
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String extractImageFromChoices(List<Object> choices) {
        for (Object c : choices) {
            if (!(c instanceof Map<?, ?> choice)) continue;
            Object message = choice.get("message");
            if (!(message instanceof Map<?, ?> msg)) continue;
            Object content = msg.get("content");
            if (!(content instanceof List<?> parts)) continue;
            for (Object p : parts) {
                if (!(p instanceof Map<?, ?> part)) continue;
                Object image = part.get("image");
                if (image instanceof String s && !s.isEmpty()) return s;
            }
        }
        return null;
    }

    private byte[] downloadImage(String imageUrl) throws IOException {
        try (InputStream in = URI.create(imageUrl).toURL().openStream()) {
            return in.readAllBytes();
        }
    }

    // ---- 异步提交响应 ----

    static class TaskSubmitResponse {
        @JsonProperty("output")
        TaskOutput output;

        static class TaskOutput {
            @JsonProperty("task_id")
            String taskId;

            @JsonProperty("task_status")
            String taskStatus;
        }
    }

}
