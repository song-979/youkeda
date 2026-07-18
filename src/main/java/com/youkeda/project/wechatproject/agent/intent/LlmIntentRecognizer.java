package com.youkeda.project.wechatproject.agent.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.wechatproject.agent.AgentProperties;
import com.youkeda.project.wechatproject.agent.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * 基于大模型的意图识别器。
 * <p>
 * 使用一个小、快、便宜的模型，通过 System Prompt 严格约束输出为 JSON 格式，
 * 解析后返回 {@link IntentResult}。调用失败时自动降级到 {@link RegexIntentRecognizer}。
 * <p>
 * System Prompt 核心策略：
 * <ul>
 *   <li>首句强约束：只输出 JSON，禁止任何额外文字</li>
 *   <li>Few-shot 示例覆盖正负样本，区分"要生图"和"讨论生图"</li>
 *   <li>ImageGen 的 prompt 要求翻译成英文，直接可喂给生图模型</li>
 * </ul>
 */
public class LlmIntentRecognizer implements IntentRecognizer {

    private static final Logger log = LoggerFactory.getLogger(LlmIntentRecognizer.class);

    private static final String SYSTEM_PROMPT = """
            你是一个意图识别器。分析用户消息，只输出一行JSON，不要输出任何其他文字、解释或markdown标记。

            规则：
            1. 如果用户想要生成、创建或绘制图片（如"画一只猫"、"生成日落照片"、"帮我做个头像"），输出：
               {"intent":"image_gen","prompt":"将用户需求转换为详细的英文图片描述"}
               prompt必须是纯英文，详细描述画面内容、风格、构图、色调。
            2. 如果用户只是在聊天、提问、讨论，输出：
               {"intent":"chat"}

            示例：
            用户："帮我画一只可爱的橘猫"
            {"intent":"image_gen","prompt":"A cute orange tabby cat with fluffy fur, big round amber eyes, sitting on a warm windowsill, soft natural lighting, cartoon illustration style, warm cozy atmosphere"}

            用户："今天天气不错"
            {"intent":"chat"}

            用户："生成一张海边日落的照片"
            {"intent":"image_gen","prompt":"A stunning sunset over the ocean, golden sun dipping below the horizon, calm waves with orange and pink reflections, palm tree silhouettes, realistic photography, 4K quality"}

            用户："你觉得AI生图怎么样？"
            {"intent":"chat"}

            用户："给我做个Logo"
            {"intent":"image_gen","prompt":"A modern minimalist logo design, clean geometric shapes, professional corporate style, flat design, vector-style, bold colors on white background"}
            """;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RestTemplate restTemplate;
    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final IntentRecognizer fallback;

    public LlmIntentRecognizer(AgentProperties props, IntentRecognizer fallback) {
        this.fallback = fallback;
        this.apiUrl = props.getIntentApiUrl() != null && !props.getIntentApiUrl().isEmpty()
                ? props.getIntentApiUrl() : props.getApiUrl();
        this.apiKey = props.getIntentApiKey() != null && !props.getIntentApiKey().isEmpty()
                ? props.getIntentApiKey() : props.getApiKey();
        this.model = props.getIntentModel() != null && !props.getIntentModel().isEmpty()
                ? props.getIntentModel() : props.getModel();
        this.restTemplate = createRestTemplate(5000, 10000);
    }

    private static RestTemplate createRestTemplate(int connectMs, int readMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectMs);
        factory.setReadTimeout(readMs);
        return new RestTemplate(factory);
    }

    @Override
    public IntentResult recognize(String text) {
        if (text == null || text.isEmpty()) {
            return fallback.recognize(text);
        }

        try {
            return doRecognize(text);
        } catch (Exception e) {
            log.warn("LLM intent recognition failed, falling back to regex. error={}", e.getMessage());
            return fallback.recognize(text);
        }
    }

    private IntentResult doRecognize(String text) {
        Map<String, Object> requestBody = buildRequestBody(text);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        log.debug("calling intent model: url={}, model={}, text={}", apiUrl, model, text);

        // 先反序列化为 ChatResponse，提取 choices[0].message.content
        ResponseEntity<ChatResponse> response = restTemplate.postForEntity(
                apiUrl, entity, ChatResponse.class);
        ChatResponse chatResponse = response.getBody();

        if (chatResponse == null) {
            log.warn("empty response from intent model");
            return fallback.recognize(text);
        }

        String content = chatResponse.extractContent();
        if (content == null || content.isEmpty()) {
            log.warn("empty content in intent model response");
            return fallback.recognize(text);
        }

        log.debug("intent model raw content: {}", content);
        return parseIntentContent(content, text);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildRequestBody(String text) {
        Map<String, Object> systemMsg = Map.of("role", "system", "content", SYSTEM_PROMPT);
        Map<String, Object> userMsg = Map.of("role", "user", "content", text);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(systemMsg, userMsg));
        body.put("temperature", 0.0);   // 零温度确保输出稳定
        body.put("max_tokens", 200);    // JSON 很短，不用太多 token
        return body;
    }

    /**
     * 从模型输出的 content 中提取 JSON 并解析为 IntentResult。
     * 处理模型可能额外输出的 markdown 代码块标记或前后文字。
     */
    @SuppressWarnings("unchecked")
    private IntentResult parseIntentContent(String content, String originalText) {
        String json = extractJson(content);
        if (json == null) {
            log.warn("cannot extract JSON from intent model content: {}", content);
            return fallback.recognize(originalText);
        }

        try {
            Map<String, Object> map = OBJECT_MAPPER.readValue(json, Map.class);
            String intent = (String) map.get("intent");

            if ("image_gen".equals(intent)) {
                String prompt = (String) map.get("prompt");
                // LLM 改写过的英文 prompt 优先，如果没有则用原始文本
                String finalPrompt = (prompt != null && !prompt.isEmpty()) ? prompt : originalText;
                return IntentResult.imageGen(finalPrompt);
            }

            return IntentResult.chat();

        } catch (Exception e) {
            log.warn("failed to parse intent JSON: json={}, error={}", json, e.getMessage());
            return fallback.recognize(originalText);
        }
    }

    /**
     * 从模型输出中提取 JSON 字符串。
     * 处理了常见情况：markdown code block 包裹、前后多余文字等。
     */
    static String extractJson(String raw) {
        if (raw == null || raw.isEmpty()) return null;

        String trimmed = raw.trim();

        // 去掉 markdown 代码块标记 ```json ... ```
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('\n');
            if (start == -1) return null;
            int end = trimmed.lastIndexOf("```");
            if (end <= start) return null;
            trimmed = trimmed.substring(start + 1, end).trim();
        }

        // 找到第一个 { 和最后一个 }
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace == -1 || lastBrace == -1 || firstBrace >= lastBrace) {
            return null;
        }

        return trimmed.substring(firstBrace, lastBrace + 1);
    }
}
