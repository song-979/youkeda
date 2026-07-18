package com.youkeda.project.wechatproject.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.ai")
public class AgentProperties {

    private boolean enabled;
    private String apiKey;
    private String apiUrl;
    private String model;
    private double temperature;
    private int maxTokens;
    private String systemPrompt;
    private int connectTimeoutMs;
    private int readTimeoutMs;

    // ---- 文生图配置 ----
    private boolean imageGenEnabled = true;
    private String imageGenApiUrl;
    private String imageGenApiKey;
    private String imageGenModel = "dall-e-3";
    private String imageGenSize = "1024x1024";
    private int imageGenN = 1;

    // ---- 意图识别配置（预留，当前 RegexIntentRecognizer 无需模型） ----
    private String intentModel;
    private String intentApiUrl;
    private String intentApiKey;

    // ---- 记忆配置 ----
    private int maxHistoryRounds = 10;
    private int memoryTtlMinutes = 30;

    // ---- getters / setters ----

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getApiUrl() { return apiUrl; }
    public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }

    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }

    public boolean isImageGenEnabled() { return imageGenEnabled; }
    public void setImageGenEnabled(boolean imageGenEnabled) { this.imageGenEnabled = imageGenEnabled; }

    public String getImageGenApiUrl() { return imageGenApiUrl; }
    public void setImageGenApiUrl(String imageGenApiUrl) { this.imageGenApiUrl = imageGenApiUrl; }

    public String getImageGenApiKey() { return imageGenApiKey; }
    public void setImageGenApiKey(String imageGenApiKey) { this.imageGenApiKey = imageGenApiKey; }

    public String getImageGenModel() { return imageGenModel; }
    public void setImageGenModel(String imageGenModel) { this.imageGenModel = imageGenModel; }

    public String getImageGenSize() { return imageGenSize; }
    public void setImageGenSize(String imageGenSize) { this.imageGenSize = imageGenSize; }

    public int getImageGenN() { return imageGenN; }
    public void setImageGenN(int imageGenN) { this.imageGenN = imageGenN; }

    public String getIntentModel() { return intentModel; }
    public void setIntentModel(String intentModel) { this.intentModel = intentModel; }

    public String getIntentApiUrl() { return intentApiUrl; }
    public void setIntentApiUrl(String intentApiUrl) { this.intentApiUrl = intentApiUrl; }

    public String getIntentApiKey() { return intentApiKey; }
    public void setIntentApiKey(String intentApiKey) { this.intentApiKey = intentApiKey; }

    public int getMaxHistoryRounds() { return maxHistoryRounds; }
    public void setMaxHistoryRounds(int maxHistoryRounds) { this.maxHistoryRounds = maxHistoryRounds; }

    public int getMemoryTtlMinutes() { return memoryTtlMinutes; }
    public void setMemoryTtlMinutes(int memoryTtlMinutes) { this.memoryTtlMinutes = memoryTtlMinutes; }
}
