package com.youkeda.project.wechatproject.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.youkeda.project.wechatproject.config.LlmProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;

@Service
public class OpenAiCompatibleChatService {

    private final RestTemplate restTemplate;
    private final LlmProperties llmProperties;

    public OpenAiCompatibleChatService(RestTemplate restTemplate, LlmProperties llmProperties) {
        this.restTemplate = restTemplate;
        this.llmProperties = llmProperties;
    }

    public boolean isAvailable() {
        return llmProperties.isTextConfigured();
    }

    public String chat(List<ChatMessage> messages) {
        return chat(messages, null);
    }

    public String chat(List<ChatMessage> messages, String modelOverride) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        String requestUrl = buildChatCompletionsUrl(llmProperties.getBaseUrl());
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel(resolveModel(modelOverride));
        request.setMessages(messages);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(llmProperties.getApiKey().trim());

        try {
            ResponseEntity<ChatCompletionResponse> response = restTemplate.exchange(
                    requestUrl,
                    HttpMethod.POST,
                    new HttpEntity<ChatCompletionRequest>(request, headers),
                    ChatCompletionResponse.class
            );

            String reply = extractReply(response.getBody());
            if (!StringUtils.hasText(reply)) {
                throw new IllegalStateException("LLM response did not contain message content.");
            }
            return reply.trim();
        } catch (HttpStatusCodeException e) {
            String responseBody = e.getResponseBodyAsString();
            throw new IllegalStateException(
                    "LLM request failed with status " + e.getRawStatusCode() + ": " + abbreviate(responseBody),
                    e
            );
        } catch (Exception e) {
            throw new IllegalStateException("LLM request failed: " + e.getMessage(), e);
        }
    }

    private String extractReply(ChatCompletionResponse response) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            return null;
        }

        Choice firstChoice = response.getChoices().get(0);
        if (firstChoice == null || firstChoice.getMessage() == null) {
            return null;
        }
        return extractContentText(firstChoice.getMessage().getContent());
    }

    private String extractContentText(Object content) {
        if (content == null) {
            return null;
        }
        if (content instanceof String) {
            return (String) content;
        }
        if (content instanceof List<?>) {
            StringBuilder builder = new StringBuilder();
            for (Object item : (List<?>) content) {
                if (!(item instanceof java.util.Map<?, ?>)) {
                    continue;
                }

                Object text = ((java.util.Map<?, ?>) item).get("text");
                if (text instanceof String && StringUtils.hasText((String) text)) {
                    if (builder.length() > 0) {
                        builder.append('\n');
                    }
                    builder.append(((String) text).trim());
                }
            }
            return builder.length() == 0 ? null : builder.toString();
        }
        return String.valueOf(content);
    }

    private String buildChatCompletionsUrl(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        if (!StringUtils.hasText(normalized)) {
            return "";
        }
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://" + normalized;
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        if (normalized.endsWith("/chat/completions")) {
            return normalized;
        }
        if (normalized.matches("^https?://[^/]+\\.maas\\.aliyuncs\\.com$")) {
            return normalized + "/compatible-mode/v1/chat/completions";
        }
        if (normalized.matches("^https?://dashscope(?:-intl|-us)?\\.aliyuncs\\.com$")) {
            return normalized + "/compatible-mode/v1/chat/completions";
        }
        if (normalized.endsWith("/compatible-mode/v1")) {
            return normalized + "/chat/completions";
        }
        if (normalized.endsWith("/v1")) {
            return normalized + "/chat/completions";
        }
        return normalized + "/v1/chat/completions";
    }

    public String getSystemPrompt() {
        return StringUtils.hasText(llmProperties.getSystemPrompt()) ? llmProperties.getSystemPrompt().trim() : null;
    }

    public String getVisionModel() {
        return StringUtils.hasText(llmProperties.getImageUnderstandingModel())
                ? llmProperties.getImageUnderstandingModel().trim()
                : null;
    }

    public String getRoutingModel() {
        return StringUtils.hasText(llmProperties.getRoutingModel())
                ? llmProperties.getRoutingModel().trim()
                : null;
    }

    private String resolveModel(String modelOverride) {
        if (StringUtils.hasText(modelOverride)) {
            return modelOverride.trim();
        }
        return llmProperties.getTextModel();
    }

    private String abbreviate(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String normalized = text.replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.length() <= 300) {
            return normalized;
        }
        return normalized.substring(0, 300) + "...";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChatCompletionRequest {

        private String model;
        private List<ChatMessage> messages = Collections.emptyList();

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public List<ChatMessage> getMessages() {
            return messages;
        }

        public void setMessages(List<ChatMessage> messages) {
            this.messages = messages;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChatMessage {

        private String role;
        private Object content;

        public ChatMessage() {
        }

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public static ChatMessage userWithImage(String text, String imageUrl) {
            List<ContentPart> contentParts = new java.util.ArrayList<ContentPart>();
            if (StringUtils.hasText(text)) {
                contentParts.add(ContentPart.text(text.trim()));
            }
            contentParts.add(ContentPart.imageUrl(imageUrl.trim()));

            ChatMessage chatMessage = new ChatMessage();
            chatMessage.setRole("user");
            chatMessage.setContent(contentParts);
            return chatMessage;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public Object getContent() {
            return content;
        }

        public void setContent(Object content) {
            this.content = content;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContentPart {

        private String type;
        private String text;
        private ImageUrl image_url;

        public static ContentPart text(String text) {
            ContentPart part = new ContentPart();
            part.setType("text");
            part.setText(text);
            return part;
        }

        public static ContentPart imageUrl(String url) {
            ContentPart part = new ContentPart();
            part.setType("image_url");
            part.setImage_url(new ImageUrl(url, "auto"));
            return part;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public ImageUrl getImage_url() {
            return image_url;
        }

        public void setImage_url(ImageUrl image_url) {
            this.image_url = image_url;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ImageUrl {

        private String url;
        private String detail;

        public ImageUrl() {
        }

        public ImageUrl(String url, String detail) {
            this.url = url;
            this.detail = detail;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getDetail() {
            return detail;
        }

        public void setDetail(String detail) {
            this.detail = detail;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChatCompletionResponse {

        private List<Choice> choices = Collections.emptyList();

        public List<Choice> getChoices() {
            return choices;
        }

        public void setChoices(List<Choice> choices) {
            this.choices = choices;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Choice {

        private ChatMessage message;

        public ChatMessage getMessage() {
            return message;
        }

        public void setMessage(ChatMessage message) {
            this.message = message;
        }
    }
}
