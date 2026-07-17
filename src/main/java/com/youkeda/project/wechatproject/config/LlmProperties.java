package com.youkeda.project.wechatproject.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    private String baseUrl;
    private String apiKey;
    private String routingModel = "qwen3.5-omni-plus-2026-03-15";
    private String textModel = "qwen-plus";
    private String imageUnderstandingModel = "qwen3.5-omni-plus-2026-03-15";
    private String imageGenerationModel = "wan2.7-image-pro";
    private String imageGenerationSize = "1024x1024";
    private String systemPrompt = "You are a concise and helpful WeChat assistant. Reply in Chinese by default.";

    public boolean isBaseConfigured() {
        return StringUtils.hasText(baseUrl) && StringUtils.hasText(apiKey);
    }

    public boolean isTextConfigured() {
        return isBaseConfigured() && StringUtils.hasText(textModel);
    }

    public boolean isRoutingConfigured() {
        return isBaseConfigured() && StringUtils.hasText(routingModel);
    }

    public boolean isImageUnderstandingConfigured() {
        return isBaseConfigured() && StringUtils.hasText(imageUnderstandingModel);
    }

    public boolean isImageGenerationConfigured() {
        return isBaseConfigured() && StringUtils.hasText(imageGenerationModel);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getRoutingModel() {
        return routingModel;
    }

    public void setRoutingModel(String routingModel) {
        this.routingModel = routingModel;
    }

    public String getTextModel() {
        return textModel;
    }

    public void setTextModel(String textModel) {
        this.textModel = textModel;
    }

    public String getImageUnderstandingModel() {
        return imageUnderstandingModel;
    }

    public void setImageUnderstandingModel(String imageUnderstandingModel) {
        this.imageUnderstandingModel = imageUnderstandingModel;
    }

    public String getImageGenerationModel() {
        return imageGenerationModel;
    }

    public void setImageGenerationModel(String imageGenerationModel) {
        this.imageGenerationModel = imageGenerationModel;
    }

    public String getImageGenerationSize() {
        return imageGenerationSize;
    }

    public void setImageGenerationSize(String imageGenerationSize) {
        this.imageGenerationSize = imageGenerationSize;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }
}
