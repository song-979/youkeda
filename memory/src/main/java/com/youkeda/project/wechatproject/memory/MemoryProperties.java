package com.youkeda.project.wechatproject.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.memory")
public class MemoryProperties {

    private boolean enabled = true;
    private int maxHistoryRounds = 10;
    private int ttlMinutes = 30;
    private String basePath = "data/memory";
    private int dailyRetentionDays = 30;
    private boolean vectorEnabled = true;
    private String indexPath;
    private String embeddingApiUrl;
    private String embeddingApiKey;
    private String embeddingModel = "text-embedding-v4";
    private int chunkChars = 900;
    private int chunkOverlapChars = 120;
    private int retrievalTopK = 6;
    private double retrievalMinScore = 0.18d;
    private String fallbackApiUrl;
    private String fallbackApiKey;
    private int connectTimeoutMs = 30000;
    private int readTimeoutMs = 180000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getMaxHistoryRounds() { return maxHistoryRounds; }
    public void setMaxHistoryRounds(int maxHistoryRounds) { this.maxHistoryRounds = maxHistoryRounds; }

    public int getTtlMinutes() { return ttlMinutes; }
    public void setTtlMinutes(int ttlMinutes) { this.ttlMinutes = ttlMinutes; }

    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = basePath; }

    public int getDailyRetentionDays() { return dailyRetentionDays; }
    public void setDailyRetentionDays(int dailyRetentionDays) { this.dailyRetentionDays = dailyRetentionDays; }

    public boolean isVectorEnabled() { return vectorEnabled; }
    public void setVectorEnabled(boolean vectorEnabled) { this.vectorEnabled = vectorEnabled; }

    public String getIndexPath() { return indexPath; }
    public void setIndexPath(String indexPath) { this.indexPath = indexPath; }

    public String getEmbeddingApiUrl() { return embeddingApiUrl; }
    public void setEmbeddingApiUrl(String embeddingApiUrl) { this.embeddingApiUrl = embeddingApiUrl; }

    public String getEmbeddingApiKey() { return embeddingApiKey; }
    public void setEmbeddingApiKey(String embeddingApiKey) { this.embeddingApiKey = embeddingApiKey; }

    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }

    public int getChunkChars() { return chunkChars; }
    public void setChunkChars(int chunkChars) { this.chunkChars = chunkChars; }

    public int getChunkOverlapChars() { return chunkOverlapChars; }
    public void setChunkOverlapChars(int chunkOverlapChars) { this.chunkOverlapChars = chunkOverlapChars; }

    public int getRetrievalTopK() { return retrievalTopK; }
    public void setRetrievalTopK(int retrievalTopK) { this.retrievalTopK = retrievalTopK; }

    public double getRetrievalMinScore() { return retrievalMinScore; }
    public void setRetrievalMinScore(double retrievalMinScore) { this.retrievalMinScore = retrievalMinScore; }

    public String getFallbackApiUrl() { return fallbackApiUrl; }
    public void setFallbackApiUrl(String fallbackApiUrl) { this.fallbackApiUrl = fallbackApiUrl; }

    public String getFallbackApiKey() { return fallbackApiKey; }
    public void setFallbackApiKey(String fallbackApiKey) { this.fallbackApiKey = fallbackApiKey; }

    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }

    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
}
