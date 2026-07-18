package com.youkeda.project.wechatproject.agent;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * OpenAI Chat Completions 响应体。
 */
public class ChatResponse {

    @JsonProperty("id")
    private String id;

    @JsonProperty("choices")
    private List<Choice> choices;

    // ---- getters / setters ----

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public List<Choice> getChoices() { return choices; }
    public void setChoices(List<Choice> choices) { this.choices = choices; }

    /**
     * 提取第一个 choice 的文本内容，失败返回 null。
     */
    public String extractContent() {
        if (choices == null || choices.isEmpty()) return null;
        Choice choice = choices.get(0);
        if (choice == null || choice.getMessage() == null) return null;
        return choice.getMessage().getContent();
    }

    // ---- inner classes ----

    public static class Choice {
        @JsonProperty("index")
        private int index;

        @JsonProperty("message")
        private Message message;

        @JsonProperty("finish_reason")
        private String finishReason;

        public int getIndex() { return index; }
        public void setIndex(int index) { this.index = index; }

        public Message getMessage() { return message; }
        public void setMessage(Message message) { this.message = message; }

        public String getFinishReason() { return finishReason; }
        public void setFinishReason(String finishReason) { this.finishReason = finishReason; }
    }

    public static class Message {
        @JsonProperty("role")
        private String role;

        @JsonProperty("content")
        private String content;

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }
}
