package com.youkeda.project.wechatproject.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * OpenAI 兼容 API 客户端，覆盖 DeepSeek / OpenAI / Ollama 等。
 * 通过配置 {@code agent.ai.api-url} 切换目标服务。
 * <p>
 * 有图片时自动构建多模态请求（OpenAI Vision 格式），
 * 模型不支持时 API 会返回错误，异常信息通过日志输出。
 */
public class OpenAiCompatibleClient implements AiModelClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);

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
    public String chat(String userMessage, List<String> imageBase64Urls) throws IOException {
        ChatRequest request = buildRequest(userMessage, imageBase64Urls);
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

            // 推测是否因模型不支持图片导致
            boolean hasImages = imageBase64Urls != null && !imageBase64Urls.isEmpty();
            if (hasImages && (errorMsg != null && (errorMsg.contains("image") || errorMsg.contains("vision") || errorMsg.contains("multimodal") || errorMsg.contains("400") || errorMsg.contains("not supported")))) {
                throw new IOException("该模型不支持图片输入，请切换到多模态模型（如 qwen-vl）。原始错误: " + errorMsg, e);
            }
            throw new IOException("AI service unavailable: " + errorMsg, e);
        }
    }

    private ChatRequest buildRequest(String userMessage, List<String> imageBase64Urls) {
        ChatRequest.Message userMsg;
        if (imageBase64Urls != null && !imageBase64Urls.isEmpty()) {
            // 多模态请求
            log.debug("building multimodal request with {} image(s)", imageBase64Urls.size());
            userMsg = new ChatRequest.Message("user", userMessage, imageBase64Urls);
        } else {
            // 纯文本请求
            userMsg = new ChatRequest.Message("user", userMessage);
        }

        List<ChatRequest.Message> messages = Arrays.asList(
                new ChatRequest.Message("system", props.getSystemPrompt()),
                userMsg
        );
        return new ChatRequest(props.getModel(), messages, props.getTemperature(), props.getMaxTokens());
    }
}
