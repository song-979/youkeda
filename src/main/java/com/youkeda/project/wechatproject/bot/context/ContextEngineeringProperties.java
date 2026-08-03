package com.youkeda.project.wechatproject.bot.context;

import com.youkeda.project.wechatproject.bot.service.AiService.AgentProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.context")
public class ContextEngineeringProperties {

    private int maxContextTokens = 16_000;
    private double reservedOutputRatio = 0.2d;
    private int recentRawHistoryMessages = 50;
    private int olderHistorySummaryMaxChars = 2_000;
    private int toolResultsMaxChars = 2_000;
    private int compressedToolResultsMaxChars = 600;
    private int taskStateMaxChars = 1_500;
    private boolean llmRelevanceEnabled = true;
    private String relevanceApiUrl;
    private String relevanceApiKey;
    private String relevanceModel;
    private int relevanceMaxTokens = 64;
    private int relevanceConnectTimeoutMs = 5_000;
    private int relevanceReadTimeoutMs = 10_000;

    public static ContextEngineeringProperties defaults() {
        return new ContextEngineeringProperties();
    }

    public ContextBudget toBudget() {
        return new ContextBudget(maxContextTokens, reservedOutputRatio);
    }

    public AgentProperties toRelevanceAgentProperties(AgentProperties defaults) {
        AgentProperties props = new AgentProperties();
        props.setEnabled(true);
        props.setApiUrl(firstNonBlank(relevanceApiUrl,
                defaults != null ? defaults.getIntentApiUrl() : null,
                defaults != null ? defaults.getApiUrl() : null));
        props.setApiKey(firstNonBlank(relevanceApiKey,
                defaults != null ? defaults.getIntentApiKey() : null,
                defaults != null ? defaults.getApiKey() : null));
        props.setModel(firstNonBlank(relevanceModel,
                defaults != null ? defaults.getIntentModel() : null,
                defaults != null ? defaults.getModel() : null));
        props.setTemperature(0.0d);
        props.setMaxTokens(relevanceMaxTokens);
        props.setConnectTimeoutMs(relevanceConnectTimeoutMs);
        props.setReadTimeoutMs(relevanceReadTimeoutMs);
        return props;
    }

    public int getMaxContextTokens() {
        return maxContextTokens;
    }

    public void setMaxContextTokens(int maxContextTokens) {
        this.maxContextTokens = Math.max(1, maxContextTokens);
    }

    public ContextEngineeringProperties withMaxContextTokens(int maxContextTokens) {
        setMaxContextTokens(maxContextTokens);
        return this;
    }

    public double getReservedOutputRatio() {
        return reservedOutputRatio;
    }

    public void setReservedOutputRatio(double reservedOutputRatio) {
        this.reservedOutputRatio = Math.min(0.9d, Math.max(0.0d, reservedOutputRatio));
    }

    public ContextEngineeringProperties withReservedOutputRatio(double reservedOutputRatio) {
        setReservedOutputRatio(reservedOutputRatio);
        return this;
    }

    public int getRecentRawHistoryMessages() {
        return recentRawHistoryMessages;
    }

    public void setRecentRawHistoryMessages(int recentRawHistoryMessages) {
        this.recentRawHistoryMessages = Math.max(0, recentRawHistoryMessages);
    }

    public ContextEngineeringProperties withRecentRawHistoryMessages(int recentRawHistoryMessages) {
        setRecentRawHistoryMessages(recentRawHistoryMessages);
        return this;
    }

    public int getOlderHistorySummaryMaxChars() {
        return olderHistorySummaryMaxChars;
    }

    public void setOlderHistorySummaryMaxChars(int olderHistorySummaryMaxChars) {
        this.olderHistorySummaryMaxChars = Math.max(200, olderHistorySummaryMaxChars);
    }

    public int getToolResultsMaxChars() {
        return toolResultsMaxChars;
    }

    public void setToolResultsMaxChars(int toolResultsMaxChars) {
        this.toolResultsMaxChars = Math.max(200, toolResultsMaxChars);
    }

    public int getCompressedToolResultsMaxChars() {
        return compressedToolResultsMaxChars;
    }

    public void setCompressedToolResultsMaxChars(int compressedToolResultsMaxChars) {
        this.compressedToolResultsMaxChars = Math.max(100, compressedToolResultsMaxChars);
    }

    public int getTaskStateMaxChars() {
        return taskStateMaxChars;
    }

    public void setTaskStateMaxChars(int taskStateMaxChars) {
        this.taskStateMaxChars = Math.max(200, taskStateMaxChars);
    }

    public boolean isLlmRelevanceEnabled() {
        return llmRelevanceEnabled;
    }

    public void setLlmRelevanceEnabled(boolean llmRelevanceEnabled) {
        this.llmRelevanceEnabled = llmRelevanceEnabled;
    }

    public String getRelevanceApiUrl() {
        return relevanceApiUrl;
    }

    public void setRelevanceApiUrl(String relevanceApiUrl) {
        this.relevanceApiUrl = relevanceApiUrl;
    }

    public String getRelevanceApiKey() {
        return relevanceApiKey;
    }

    public void setRelevanceApiKey(String relevanceApiKey) {
        this.relevanceApiKey = relevanceApiKey;
    }

    public String getRelevanceModel() {
        return relevanceModel;
    }

    public void setRelevanceModel(String relevanceModel) {
        this.relevanceModel = relevanceModel;
    }

    public int getRelevanceMaxTokens() {
        return relevanceMaxTokens;
    }

    public void setRelevanceMaxTokens(int relevanceMaxTokens) {
        this.relevanceMaxTokens = Math.max(16, relevanceMaxTokens);
    }

    public int getRelevanceConnectTimeoutMs() {
        return relevanceConnectTimeoutMs;
    }

    public void setRelevanceConnectTimeoutMs(int relevanceConnectTimeoutMs) {
        this.relevanceConnectTimeoutMs = Math.max(500, relevanceConnectTimeoutMs);
    }

    public int getRelevanceReadTimeoutMs() {
        return relevanceReadTimeoutMs;
    }

    public void setRelevanceReadTimeoutMs(int relevanceReadTimeoutMs) {
        this.relevanceReadTimeoutMs = Math.max(1_000, relevanceReadTimeoutMs);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
