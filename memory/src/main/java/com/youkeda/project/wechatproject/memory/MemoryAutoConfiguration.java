package com.youkeda.project.wechatproject.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.nio.file.Path;

/**
 * Dedicated wiring module for conversation memory.
 */
@Configuration
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MemoryAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ConversationMemory conversationMemory(MemoryProperties props, Environment env) {
        MemoryProperties effective = effectiveProperties(props, env);
        log.info("creating OpenClawConversationMemory path={}, maxRounds={}, ttlMin={}, episodeRetentionDays={}",
                effective.getBasePath(), effective.getMaxHistoryRounds(), effective.getTtlMinutes(),
                effective.getDailyRetentionDays());
        VectorMemoryIndex vectorIndex = createMemoryVectorIndex(effective);
        return new OpenClawConversationMemory(
                effective.getMaxHistoryRounds(),
                effective.getTtlMinutes(),
                effective.getBasePath(),
                effective.getDailyRetentionDays(),
                vectorIndex);
    }

    private VectorMemoryIndex createMemoryVectorIndex(MemoryProperties props) {
        if (!props.isVectorEnabled()) {
            log.info("OpenClaw memory vector retrieval disabled");
            return null;
        }

        String embeddingKey = firstNonBlank(props.getEmbeddingApiKey(), props.getFallbackApiKey());
        if (embeddingKey == null || embeddingKey.isBlank()) {
            log.warn("OpenClaw memory vector retrieval disabled because no embedding API key is configured");
            return null;
        }

        Path indexPath = props.getIndexPath() == null || props.getIndexPath().isBlank()
                ? Path.of("lib.db").toAbsolutePath().normalize()
                : Path.of(props.getIndexPath()).toAbsolutePath().normalize();
        MemoryEmbeddingClient embeddingClient = new OpenAiCompatibleMemoryEmbeddingClient(props);
        log.info("creating OpenClaw SQLite memory vector index path={}, model={}, topK={}",
                indexPath, props.getEmbeddingModel(), props.getRetrievalTopK());
        return new VectorMemoryIndex(
                indexPath,
                embeddingClient,
                props.getChunkChars(),
                props.getChunkOverlapChars(),
                props.getRetrievalTopK(),
                props.getRetrievalMinScore(),
                props.getEmbeddingModel());
    }

    private MemoryProperties effectiveProperties(MemoryProperties props, Environment env) {
        MemoryProperties effective = new MemoryProperties();
        effective.setMaxHistoryRounds(getInt(env, "agent.memory.max-history-rounds",
                "agent.ai.max-history-rounds", props.getMaxHistoryRounds()));
        effective.setTtlMinutes(getInt(env, "agent.memory.ttl-minutes",
                "agent.ai.memory-ttl-minutes", props.getTtlMinutes()));
        effective.setBasePath(getString(env, "agent.memory.base-path",
                "agent.ai.memory-base-path", props.getBasePath()));
        effective.setDailyRetentionDays(getInt(env, "agent.memory.daily-retention-days",
                "agent.ai.daily-memory-retention-days", props.getDailyRetentionDays()));
        effective.setVectorEnabled(getBoolean(env, "agent.memory.vector-enabled",
                "agent.ai.memory-vector-enabled", props.isVectorEnabled()));
        effective.setIndexPath(getString(env, "agent.memory.index-path",
                "agent.ai.memory-index-path", props.getIndexPath()));
        effective.setEmbeddingApiUrl(getString(env, "agent.memory.embedding-api-url",
                "agent.ai.memory-embedding-api-url", props.getEmbeddingApiUrl()));
        effective.setEmbeddingApiKey(getString(env, "agent.memory.embedding-api-key",
                "agent.ai.memory-embedding-api-key", props.getEmbeddingApiKey()));
        effective.setEmbeddingModel(getString(env, "agent.memory.embedding-model",
                "agent.ai.memory-embedding-model", props.getEmbeddingModel()));
        effective.setChunkChars(getInt(env, "agent.memory.chunk-chars",
                "agent.ai.memory-chunk-chars", props.getChunkChars()));
        effective.setChunkOverlapChars(getInt(env, "agent.memory.chunk-overlap-chars",
                "agent.ai.memory-chunk-overlap-chars", props.getChunkOverlapChars()));
        effective.setRetrievalTopK(getInt(env, "agent.memory.retrieval-top-k",
                "agent.ai.memory-retrieval-top-k", props.getRetrievalTopK()));
        effective.setRetrievalMinScore(getDouble(env, "agent.memory.retrieval-min-score",
                "agent.ai.memory-retrieval-min-score", props.getRetrievalMinScore()));
        effective.setFallbackApiUrl(getString(env, "agent.memory.fallback-api-url",
                "agent.ai.api-url", props.getFallbackApiUrl()));
        effective.setFallbackApiKey(getString(env, "agent.memory.fallback-api-key",
                "agent.ai.api-key", props.getFallbackApiKey()));
        effective.setConnectTimeoutMs(getInt(env, "agent.memory.connect-timeout-ms",
                "agent.ai.connect-timeout-ms", props.getConnectTimeoutMs()));
        effective.setReadTimeoutMs(getInt(env, "agent.memory.read-timeout-ms",
                "agent.ai.read-timeout-ms", props.getReadTimeoutMs()));
        return effective;
    }

    private static String getString(Environment env, String key, String legacyKey, String fallback) {
        String value = env.getProperty(key);
        if (value != null) {
            return value;
        }
        value = env.getProperty(legacyKey);
        return value != null ? value : fallback;
    }

    private static int getInt(Environment env, String key, String legacyKey, int fallback) {
        Integer value = env.getProperty(key, Integer.class);
        if (value != null) {
            return value;
        }
        value = env.getProperty(legacyKey, Integer.class);
        return value != null ? value : fallback;
    }

    private static boolean getBoolean(Environment env, String key, String legacyKey, boolean fallback) {
        Boolean value = env.getProperty(key, Boolean.class);
        if (value != null) {
            return value;
        }
        value = env.getProperty(legacyKey, Boolean.class);
        return value != null ? value : fallback;
    }

    private static double getDouble(Environment env, String key, String legacyKey, double fallback) {
        Double value = env.getProperty(key, Double.class);
        if (value != null) {
            return value;
        }
        value = env.getProperty(legacyKey, Double.class);
        return value != null ? value : fallback;
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }
}
