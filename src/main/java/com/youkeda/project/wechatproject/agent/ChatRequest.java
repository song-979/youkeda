package com.youkeda.project.wechatproject.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI Chat Completions 请求体。
 * 兼容 DeepSeek / OpenAI / Ollama 等所有 OpenAI-format API。
 * <p>
 * content 支持两种格式：
 * <ul>
 *   <li>纯文本：{@code "content": "文本内容"}</li>
 *   <li>多模态：{@code "content": [{"type":"text","text":"..."},{"type":"image_url",...}]}</li>
 * </ul>
 */
class ChatRequest {

    @JsonProperty("model")
    private String model;

    @JsonProperty("messages")
    private List<Message> messages;

    @JsonProperty("temperature")
    private double temperature;

    @JsonProperty("max_tokens")
    private int maxTokens;

    ChatRequest() {
    }

    ChatRequest(String model, List<Message> messages, double temperature, int maxTokens) {
        this.model = model;
        this.messages = messages;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    // ---- getters / setters ----

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public List<Message> getMessages() { return messages; }
    public void setMessages(List<Message> messages) { this.messages = messages; }

    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    // ==================== Message ====================

    static class Message {
        @JsonProperty("role")
        private String role;

        /**
         * 纯文本时是 String，多模态时是 List&lt;ContentPart&gt;。
         */
        @JsonProperty("content")
        private Object content;

        Message() {
        }

        /** 纯文本消息 */
        Message(String role, String textContent) {
            this.role = role;
            this.content = textContent;
        }

        /** 多模态消息（文本 + 图片） */
        Message(String role, String text, List<String> imageBase64Urls) {
            this.role = role;
            List<ContentPart> parts = new ArrayList<>();
            if (text != null && !text.isEmpty()) {
                parts.add(ContentPart.text(text));
            }
            for (String url : imageBase64Urls) {
                parts.add(ContentPart.imageUrl(url));
            }
            this.content = parts;
        }

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }

        public Object getContent() { return content; }
        public void setContent(Object content) { this.content = content; }
    }

    // ==================== ContentPart（多模态） ====================

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class ContentPart {
        @JsonProperty("type")
        private String type;

        @JsonProperty("text")
        private String text;

        @JsonProperty("image_url")
        private ImageUrl imageUrl;

        ContentPart() {
        }

        static ContentPart text(String text) {
            ContentPart part = new ContentPart();
            part.type = "text";
            part.text = text;
            return part;
        }

        static ContentPart imageUrl(String url) {
            ContentPart part = new ContentPart();
            part.type = "image_url";
            part.imageUrl = new ImageUrl(url);
            return part;
        }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }

        public ImageUrl getImageUrl() { return imageUrl; }
        public void setImageUrl(ImageUrl imageUrl) { this.imageUrl = imageUrl; }
    }

    // ==================== ImageUrl ====================

    static class ImageUrl {
        @JsonProperty("url")
        private String url;

        ImageUrl() {
        }

        ImageUrl(String url) {
            this.url = url;
        }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }
}
