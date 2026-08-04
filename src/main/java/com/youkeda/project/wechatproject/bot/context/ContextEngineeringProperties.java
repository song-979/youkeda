package com.youkeda.project.wechatproject.bot.context;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.context")
public class ContextEngineeringProperties {

    private double reservedOutputRatio = 0.2d;
    private int recentRawHistoryMessages = 12;
    private int olderHistorySummaryMaxChars = 2_000;
    private int toolResultsMaxChars = 2_000;
    private int compressedToolResultsMaxChars = 600;
    private int recentRawToolRounds = 4;
    private String toolTranscriptStorePath = "data/context/tool-transcripts";
    private int toolTranscriptRetentionDays = 7;
    private int taskStateMaxChars = 1_500;
    private boolean llmRelevanceEnabled = true;

    public static ContextEngineeringProperties defaults() {
        return new ContextEngineeringProperties();
    }

    public ContextBudget toBudget(int contextWindowTokens) {
        return new ContextBudget(contextWindowTokens, reservedOutputRatio);
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

    public int getRecentRawToolRounds() {
        return recentRawToolRounds;
    }

    public void setRecentRawToolRounds(int recentRawToolRounds) {
        this.recentRawToolRounds = Math.max(1, recentRawToolRounds);
    }

    public String getToolTranscriptStorePath() {
        return toolTranscriptStorePath;
    }

    public void setToolTranscriptStorePath(String toolTranscriptStorePath) {
        if (toolTranscriptStorePath != null && !toolTranscriptStorePath.isBlank()) {
            this.toolTranscriptStorePath = toolTranscriptStorePath;
        }
    }

    public int getToolTranscriptRetentionDays() {
        return toolTranscriptRetentionDays;
    }

    public void setToolTranscriptRetentionDays(int toolTranscriptRetentionDays) {
        this.toolTranscriptRetentionDays = Math.max(1, toolTranscriptRetentionDays);
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

}
