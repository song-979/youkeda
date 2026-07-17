package com.youkeda.project.wechatproject.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ilink.bot")
public class IlinkBotProperties {

    private boolean enabled = true;
    private String fixedReply = "Hello, I am temporarily unable to reach the LLM service.";
    private String imageReply = "I can only process text messages for now.";
    private String imagePrompt = "Please describe the image briefly in Chinese and answer the user's likely intent if it is obvious.";
    private String imageReplyCaption = "Here is your generated image.";
    private long pollIntervalMs = 1000L;
    private long typingDelayMs = 800L;
    private int maxHistoryMessages = 12;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getFixedReply() {
        return fixedReply;
    }

    public void setFixedReply(String fixedReply) {
        this.fixedReply = fixedReply;
    }

    public String getImageReply() {
        return imageReply;
    }

    public void setImageReply(String imageReply) {
        this.imageReply = imageReply;
    }

    public String getImagePrompt() {
        return imagePrompt;
    }

    public void setImagePrompt(String imagePrompt) {
        this.imagePrompt = imagePrompt;
    }

    public String getImageReplyCaption() {
        return imageReplyCaption;
    }

    public void setImageReplyCaption(String imageReplyCaption) {
        this.imageReplyCaption = imageReplyCaption;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public long getTypingDelayMs() {
        return typingDelayMs;
    }

    public void setTypingDelayMs(long typingDelayMs) {
        this.typingDelayMs = typingDelayMs;
    }

    public int getMaxHistoryMessages() {
        return maxHistoryMessages;
    }

    public void setMaxHistoryMessages(int maxHistoryMessages) {
        this.maxHistoryMessages = maxHistoryMessages;
    }
}
