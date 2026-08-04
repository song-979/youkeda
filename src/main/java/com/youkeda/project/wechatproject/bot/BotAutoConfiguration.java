package com.youkeda.project.wechatproject.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.context.ConversationContext;
import com.github.wechat.ilink.sdk.core.context.ContextKey;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.youkeda.project.wechatproject.bot.handler.MessageHandler;
import com.youkeda.project.wechatproject.bot.context.CharacterContextTokenEstimator;
import com.youkeda.project.wechatproject.bot.context.ContextEngineeringProperties;
import com.youkeda.project.wechatproject.bot.context.ContextEngineeringService;
import com.youkeda.project.wechatproject.bot.context.ContextRelevanceClassifier;
import com.youkeda.project.wechatproject.bot.context.ContextTokenEstimator;
import com.youkeda.project.wechatproject.bot.context.DefaultContextEngineeringService;
import com.youkeda.project.wechatproject.bot.context.LlmContextRelevanceClassifier;
import com.youkeda.project.wechatproject.bot.context.RuleBasedContextRelevanceClassifier;
import com.youkeda.project.wechatproject.bot.tool.chat.AutomationRuntime;
import com.youkeda.project.wechatproject.bot.tool.chat.SkillTools;
import com.youkeda.project.wechatproject.bot.service.AiService.AgentProperties;
import com.youkeda.project.wechatproject.bot.service.AiService.AiModelClient;
import com.youkeda.project.wechatproject.bot.service.AiService.DashScopeImageGenClient;
import com.youkeda.project.wechatproject.bot.service.AiService.ImageGenClient;
import com.youkeda.project.wechatproject.bot.service.AiService.OpenAiCompatibleClient;
import com.youkeda.project.wechatproject.bot.service.AiService.OpenAiImageGenClient;
import com.youkeda.project.wechatproject.bot.service.BotService.ContextPersister;
import com.youkeda.project.wechatproject.bot.service.BotService.IlinkClientLifecycle;
import com.youkeda.project.wechatproject.bot.service.BotService.IlinkProperties;
import com.youkeda.project.wechatproject.bot.service.BotService.MessageBridge;

import com.youkeda.project.wechatproject.bot.service.DocumentService;
import com.youkeda.project.wechatproject.bot.agent.AgentRegistry;
import com.youkeda.project.wechatproject.bot.agent.AgentUnit;
import com.youkeda.project.wechatproject.bot.agent.chat.ChatAgent;
import com.youkeda.project.wechatproject.bot.agent.imagegen.ImageGenAgent;
import com.youkeda.project.wechatproject.bot.agent.speech.SpeechAgent;
import com.youkeda.project.wechatproject.bot.model.ModelReply;
import com.youkeda.project.wechatproject.bot.agent.browser.BrowserAgent;
import com.youkeda.project.wechatproject.bot.tool.browser.BrowserTools;
import com.youkeda.project.wechatproject.bot.agent.travel.TravelAgent;
import com.youkeda.project.wechatproject.bot.memory.AgentMemory;
import com.youkeda.project.wechatproject.bot.memory.ConversationMemory;
import com.youkeda.project.wechatproject.bot.memory.FileBasedAgentMemory;
import com.youkeda.project.wechatproject.bot.memory.OpenClawConversationMemory;
import com.youkeda.project.wechatproject.bot.memory.RagStore;
import com.youkeda.project.wechatproject.bot.memory.SqliteRagStore;
import com.youkeda.project.wechatproject.bot.memory.VectorMemoryIndex;
import com.youkeda.project.wechatproject.bot.service.AiService.EmbeddingClient;
import com.youkeda.project.wechatproject.bot.service.AiService.OpenAiCompatibleEmbeddingClient;
import com.youkeda.project.wechatproject.bot.orchestrator.DagPlanningAgentImpl;
import com.youkeda.project.wechatproject.bot.orchestrator.DagOrchestrationProperties;
import com.youkeda.project.wechatproject.bot.router.MessageRouter;
import com.youkeda.project.wechatproject.bot.router.SimpleModeRouter;
import com.youkeda.project.wechatproject.bot.router.TaskComplexityRouter;
import com.youkeda.project.wechatproject.bot.service.VoiceService.AudioConverter;
import com.youkeda.project.wechatproject.bot.service.VoiceService.FunAsrSttClient;
import com.youkeda.project.wechatproject.bot.service.VoiceService.Qwen3TtsFlashClient;
import com.youkeda.project.wechatproject.bot.service.VoiceService.SpeechProperties;
import com.youkeda.project.wechatproject.bot.service.VoiceService.SpeechToTextClient;
import com.youkeda.project.wechatproject.bot.service.VoiceService.TextToSpeechClient;
import com.youkeda.project.wechatproject.bot.service.VoiceService.VoiceCatalog;
import com.youkeda.project.wechatproject.bot.tool.chat.ScheduledTaskExecutionResult;
import com.youkeda.project.wechatproject.bot.tool.chat.ScheduledTaskExecutor;
import com.youkeda.project.wechatproject.bot.workflow.DagOrchestrationService;
import com.youkeda.project.wechatproject.bot.workflow.DagPlanningAgent;
import com.youkeda.project.wechatproject.bot.workflow.DagTaskStore;
import com.youkeda.project.wechatproject.bot.workflow.SqliteDagTaskStore;
import com.youkeda.project.wechatproject.bot.tool.ToolService.ToolChatClientFactory;
import com.youkeda.project.wechatproject.bot.tool.ToolService.ToolRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.backoff.NoBackOffPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.List;

@Configuration
@EnableConfigurationProperties({
        IlinkProperties.class,
        AgentProperties.class,
        DagOrchestrationProperties.class,
        ContextEngineeringProperties.class,
        SpeechProperties.class
})
public class BotAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BotAutoConfiguration.class);

    @Bean
    public RestClient.Builder restClientBuilder() {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        return RestClient.builder().requestFactory(new JdkClientHttpRequestFactory(httpClient));
    }

    @Bean
    @ConditionalOnMissingBean
    public ResponseErrorHandler responseErrorHandler() {
        return new DefaultResponseErrorHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public RetryTemplate retryTemplate() {
        RetryTemplate template = new RetryTemplate();
        template.setBackOffPolicy(new NoBackOffPolicy());
        return template;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ilink", name = "enabled", havingValue = "true", matchIfMissing = true)
    public MessageBridge messageBridge() {
        return new MessageBridge();
    }

    private static final String RESUME_CONTEXT_PATH = "data/ilink-resume/resume-context.json";
    private static final String RESUME_KEY_PATH = "data/ilink-resume/resume-context.key";
    private static final ObjectMapper RESUME_MAPPER = new ObjectMapper();

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ilink", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ILinkClient ilinkClient(IlinkProperties props, MessageBridge bridge) {
        ILinkConfig config = ILinkConfig.builder()
                .connectTimeoutMs(props.getConnectTimeoutMs())
                .readTimeoutMs(props.getReadTimeoutMs())
                .writeTimeoutMs(props.getWriteTimeoutMs())
                .httpMaxRetries(props.getHttpMaxRetries())
                .loginTimeoutMs(props.getLoginTimeoutMs())
                .heartbeatEnabled(props.isHeartbeatEnabled())
                .heartbeatIntervalMs(props.getHeartbeatIntervalMs())
                .build();

        ResumeContext resumeContext = loadResumeContext();
        if (resumeContext != null) {
            log.info("resume context loaded: {} conversation contexts",
                    resumeContext.getConversationContextMap().size());
        }

        return ILinkClient.builder()
                .config(config)
                .onMessage(bridge)
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(com.github.wechat.ilink.sdk.core.login.LoginContext ctx) {
                        log.info("iLink login success: botId={}, userId={}", ctx.getBotId(), ctx.getUserId());
                    }

                    @Override
                    public void onLoginFailure(Throwable ex) {
                        log.error("iLink login failed", ex);
                    }
                })
                .resumeContext(resumeContext)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ilink", name = "enabled", havingValue = "true", matchIfMissing = true)
    public IlinkClientLifecycle ilinkClientLifecycle(ILinkClient ilinkClient,
                                                     MessageBridge messageBridge,
                                                     IlinkProperties props) {
        return new IlinkClientLifecycle(ilinkClient, messageBridge, props, this::saveResumeContext);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ilink", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ContextPersister contextPersister(ILinkClient ilinkClient,
                                             MessageBridge messageBridge) {
        return new ContextPersister(ilinkClient, messageBridge, this::saveResumeContext);
    }

    private ResumeContext loadResumeContext() {
        java.nio.file.Path path = java.nio.file.Path.of(RESUME_CONTEXT_PATH);
        if (!java.nio.file.Files.exists(path)) {
            log.info("no resume context file at {}", RESUME_CONTEXT_PATH);
            return null;
        }
        try {
            String content = java.nio.file.Files.readString(path);
            JsonNode root = RESUME_MAPPER.readTree(content);
            if (root.hasNonNull("ciphertext")) {
                root = RESUME_MAPPER.readTree(decryptResumePayload(root.get("ciphertext").asText()));
            } else {
                log.warn("loading legacy plaintext resume context; it will be encrypted on the next save");
            }

            JsonNode lc = root.get("loginContext");
            if (lc == null) {
                log.warn("resume context file missing loginContext field");
                return null;
            }
            LoginContext loginContext = new LoginContext(
                    lc.get("botToken").asText(),
                    lc.get("userId").asText(),
                    lc.get("botId").asText(),
                    lc.get("baseUrl").asText());

            String updatesCursor = root.has("updatesCursor") ? root.get("updatesCursor").asText() : null;

            String currentBotId = loginContext.getBotId();
            java.util.Map<String, ConversationContext> contexts = new java.util.LinkedHashMap<>();
            JsonNode cc = root.get("conversationContexts");
            int skipped = 0;
            if (cc != null && cc.isObject()) {
                var fields = cc.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    String userId = entry.getKey();
                    JsonNode ctxNode = entry.getValue();
                    String botId = ctxNode.get("botId").asText();
                    if (!currentBotId.equals(botId)) {
                        log.info("skipping conversation context for userId={}, botId={} != current botId={}",
                                userId, botId, currentBotId);
                        skipped++;
                        continue;
                    }
                    ConversationContext ctx = new ConversationContext(new ContextKey(botId, userId));
                    if (ctxNode.has("latestContextToken") && !ctxNode.get("latestContextToken").isNull()) {
                        ctx.setLatestContextToken(ctxNode.get("latestContextToken").asText());
                    }
                    if (ctxNode.has("sourceMessageId") && !ctxNode.get("sourceMessageId").isNull()) {
                        ctx.updateContextToken(
                                ctx.getLatestContextToken(),
                                ctxNode.get("sourceMessageId").asLong(),
                                ctxNode.has("sourceMessageTime") && !ctxNode.get("sourceMessageTime").isNull()
                                        ? ctxNode.get("sourceMessageTime").asLong() : null);
                    }
                    contexts.put(userId, ctx);
                }
            }
            if (skipped > 0) {
                log.warn("skipped {} conversation contexts due to botId mismatch (current bot: {})",
                        skipped, currentBotId);
            }

            ResumeContext result = ResumeContext.builder(loginContext)
                    .updatesCursor(updatesCursor)
                    .conversationContexts(contexts)
                    .build();
            log.info("resume context loaded: {} conversations, cursor={}",
                    contexts.size(), updatesCursor != null ? "present" : "absent");
            return result;

        } catch (Exception e) {
            log.warn("failed to load resume context from {}: {}", RESUME_CONTEXT_PATH, e.getMessage());
            return null;
        }
    }

    private void saveResumeContext(ResumeContext resumeContext) {
        if (resumeContext == null) {
            return;
        }
        try {
            var root = RESUME_MAPPER.createObjectNode();

            LoginContext lc = resumeContext.getLoginContext();
            var lcNode = RESUME_MAPPER.createObjectNode();
            lcNode.put("botToken", lc.getBotToken());
            lcNode.put("userId", lc.getUserId());
            lcNode.put("botId", lc.getBotId());
            lcNode.put("baseUrl", lc.getBaseUrl());
            root.set("loginContext", lcNode);

            if (resumeContext.getUpdatesCursor() != null) {
                root.put("updatesCursor", resumeContext.getUpdatesCursor());
            }

            var ccNode = RESUME_MAPPER.createObjectNode();
            for (var entry : resumeContext.getConversationContextMap().entrySet()) {
                var ctx = entry.getValue();
                if (ctx == null || ctx.getLatestContextToken() == null) {
                    continue;
                }
                var ctxNode = RESUME_MAPPER.createObjectNode();
                ctxNode.put("botId", ctx.getKey().getBotId());
                ctxNode.put("userId", ctx.getKey().getUserId());
                ctxNode.put("latestContextToken", ctx.getLatestContextToken());
                if (ctx.getSourceMessageId() != null) {
                    ctxNode.put("sourceMessageId", ctx.getSourceMessageId());
                }
                if (ctx.getSourceMessageTime() != null) {
                    ctxNode.put("sourceMessageTime", ctx.getSourceMessageTime());
                }
                ccNode.set(entry.getKey(), ctxNode);
            }
            root.set("conversationContexts", ccNode);

            java.nio.file.Path dir = java.nio.file.Path.of(RESUME_CONTEXT_PATH).getParent();
            java.nio.file.Files.createDirectories(dir);
            String encrypted = encryptResumePayload(
                    RESUME_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            var envelope = RESUME_MAPPER.createObjectNode();
            envelope.put("format", "aes-gcm-v1");
            envelope.put("ciphertext", encrypted);
            java.nio.file.Path target = java.nio.file.Path.of(RESUME_CONTEXT_PATH);
            java.nio.file.Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            java.nio.file.Files.writeString(temp,
                    RESUME_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(envelope));
            try {
                java.nio.file.Files.move(temp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("resume context saved to {}", RESUME_CONTEXT_PATH);

        } catch (Exception e) {
            log.error("failed to save resume context: {}", e.getMessage(), e);
        }
    }

    private static String encryptResumePayload(String plaintext) throws Exception {
        byte[] nonce = new byte[12];
        new java.security.SecureRandom().nextBytes(nonce);
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(loadResumeKey(), "AES"),
                new javax.crypto.spec.GCMParameterSpec(128, nonce));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] envelope = java.nio.ByteBuffer.allocate(nonce.length + ciphertext.length)
                .put(nonce).put(ciphertext).array();
        return java.util.Base64.getEncoder().encodeToString(envelope);
    }

    private static String decryptResumePayload(String encoded) throws Exception {
        byte[] envelope = java.util.Base64.getDecoder().decode(encoded);
        if (envelope.length < 29) {
            throw new java.security.GeneralSecurityException("invalid encrypted resume context");
        }
        byte[] nonce = java.util.Arrays.copyOfRange(envelope, 0, 12);
        byte[] ciphertext = java.util.Arrays.copyOfRange(envelope, 12, envelope.length);
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(loadResumeKey(), "AES"),
                new javax.crypto.spec.GCMParameterSpec(128, nonce));
        return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] loadResumeKey() throws Exception {
        String configured = System.getenv("WECHAT_RESUME_CONTEXT_KEY");
        if (configured != null && !configured.isBlank()) {
            byte[] key = java.util.Base64.getDecoder().decode(configured.trim());
            if (key.length != 32) {
                throw new IllegalArgumentException("WECHAT_RESUME_CONTEXT_KEY must be a base64-encoded 32-byte key");
            }
            return key;
        }

        java.nio.file.Path keyPath = java.nio.file.Path.of(RESUME_KEY_PATH);
        if (java.nio.file.Files.exists(keyPath)) {
            byte[] key = java.util.Base64.getDecoder().decode(java.nio.file.Files.readString(keyPath).trim());
            if (key.length != 32) {
                throw new java.security.GeneralSecurityException("invalid resume context key file");
            }
            return key;
        }
        java.nio.file.Files.createDirectories(keyPath.getParent());
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        try {
            java.nio.file.Files.writeString(keyPath, java.util.Base64.getEncoder().encodeToString(key),
                    java.nio.file.StandardOpenOption.CREATE_NEW);
        } catch (java.nio.file.FileAlreadyExistsException race) {
            return loadResumeKey();
        }
        try {
            java.nio.file.Files.setPosixFilePermissions(keyPath,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows ACLs inherit from the private application data directory.
        }
        return key;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AiModelClient aiModelClient(AgentProperties props) {
        log.info("creating OpenAiCompatibleClient for model={}, url={}", props.getModel(), props.getApiUrl());
        return new OpenAiCompatibleClient(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextTokenEstimator contextTokenEstimator() {
        return new CharacterContextTokenEstimator();
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextRelevanceClassifier contextRelevanceClassifier(
            AgentProperties props, ContextEngineeringProperties contextProperties) {
        ContextRelevanceClassifier rules = new RuleBasedContextRelevanceClassifier();
        if (!props.isEnabled() || !contextProperties.isLlmRelevanceEnabled()) {
            return rules;
        }
        String intentModel = props.getIntentModel() != null && !props.getIntentModel().isBlank()
                ? props.getIntentModel() : props.getModel();
        return new LlmContextRelevanceClassifier(
                OpenAiCompatibleClient.forIntent(props), rules, intentModel);
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextEngineeringService contextEngineeringService(
            ContextRelevanceClassifier classifier,
            ContextTokenEstimator tokenEstimator,
            ContextEngineeringProperties contextProperties,
            AgentProperties agentProperties) {
        return new DefaultContextEngineeringService(
                classifier,
                tokenEstimator,
                contextProperties,
                contextProperties.toBudget(agentProperties.getContextWindowTokens()));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression("${agent.ai.enabled:true} && ${agent.ai.image-gen-enabled:false}")
    public ImageGenClient imageGenClient(AgentProperties props) {
        log.info("creating OpenAiImageGenClient for model={}, url={}",
                props.getImageGenModel(), props.getImageGenApiUrl());
        return new OpenAiImageGenClient(props);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
    public EmbeddingClient embeddingClient(AgentProperties props) {
        log.info("creating OpenAiCompatibleEmbeddingClient for embedding model={}", props.getMemoryEmbeddingModel());
        return new OpenAiCompatibleEmbeddingClient(props);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
    public VectorMemoryIndex memoryVectorIndex(EmbeddingClient embeddingClient, AgentProperties props) {
        String indexPath = props.getMemoryIndexPath();
        if (indexPath == null || indexPath.isBlank()) {
            indexPath = props.getMemoryBasePath() + "/memory-index.db";
        }
        log.info("creating VectorMemoryIndex at {}", indexPath);
        return new VectorMemoryIndex(
                java.nio.file.Path.of(indexPath),
                embeddingClient,
                props.getMemoryChunkChars(),
                props.getMemoryChunkOverlapChars(),
                props.getMemoryRetrievalTopK(),
                props.getMemoryRetrievalMinScore(),
                props.getMemoryEmbeddingModel());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.rag", name = "enabled", havingValue = "true")
    public RagStore ragStore(EmbeddingClient embeddingClient, AgentProperties props) {
        String indexPath = props.getMemoryIndexPath();
        if (indexPath == null || indexPath.isBlank()) {
            indexPath = props.getMemoryBasePath() + "/memory-index.db";
        }
        log.info("creating SqliteRagStore at {}, chunkChars={}, topK={}",
                indexPath, props.getRagChunkChars(), props.getRagTopK());
        return new SqliteRagStore(
                java.nio.file.Path.of(indexPath),
                embeddingClient,
                props.getRagChunkChars(),
                props.getRagChunkOverlapChars(),
                props.getRagTopK(),
                props.getRagMinScore(),
                props.getMemoryEmbeddingModel());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ConversationMemory conversationMemory(AgentProperties props,
                                                   ObjectProvider<VectorMemoryIndex> vectorIndexProvider,
                                                   ObjectProvider<AiModelClient> aiClientProvider) {
        VectorMemoryIndex vectorIndex = props.isMemoryVectorEnabled()
                ? vectorIndexProvider.getIfAvailable() : null;
        AiModelClient aiClient = aiClientProvider.getIfAvailable();
        log.info("creating OpenClawConversationMemory basePath={}, maxRounds={}, ttlMin={}, vectorEnabled={}, llmSummary={}",
                props.getMemoryBasePath(), props.getMaxHistoryRounds(),
                props.getMemoryTtlMinutes(), vectorIndex != null, aiClient != null);
        return new OpenClawConversationMemory(
                props.getMaxHistoryRounds(),
                props.getMemoryTtlMinutes(),
                props.getMemoryBasePath(),
                props.getDailyMemoryRetentionDays(),
                vectorIndex,
                aiClient);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
    public VoiceCatalog voiceCatalog() {
        log.info("creating VoiceCatalog");
        return new VoiceCatalog();
    }

    @Bean
    @ConditionalOnMissingBean(name = "chatAgentMemory")
    public AgentMemory chatAgentMemory(AgentProperties props) {
        return new FileBasedAgentMemory("CHAT", props.getMemoryBasePath(), props.getMemoryTtlMinutes() * 60L);
    }

    @Bean
    @ConditionalOnMissingBean(name = "browserAgentMemory")
    public AgentMemory browserAgentMemory(AgentProperties props) {
        return new FileBasedAgentMemory("BROWSER", props.getMemoryBasePath(), props.getMemoryTtlMinutes() * 60L);
    }

    @Bean
    @ConditionalOnMissingBean(name = "travelAgentMemory")
    public AgentMemory travelAgentMemory(AgentProperties props) {
        return new FileBasedAgentMemory("TRAVEL", props.getMemoryBasePath(), props.getMemoryTtlMinutes() * 60L);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ChatAgent chatAgent(AiModelClient aiModelClient,
                               AgentProperties props,
                               ObjectProvider<ToolChatClientFactory> toolChatClientFactoryProvider,
                               ObjectProvider<ToolRuntime> toolRuntimeProvider,
                               ObjectProvider<SkillTools> skillToolsProvider,
                               @org.springframework.beans.factory.annotation.Qualifier("chatAgentMemory") AgentMemory chatAgentMemory,
                               ContextEngineeringService contextEngineeringService) {
        log.info("creating ChatAgent");
        ToolRuntime toolRuntime = toolRuntimeProvider.getIfAvailable();
        String categories = toolRuntime != null ? toolRuntime.getCategorySummary() : "";
        SkillTools skillTools = skillToolsProvider.getIfAvailable();
        String skillsSummary = skillTools != null ? skillTools.getSkillsSummary("CHAT") : "";
        return new ChatAgent(aiModelClient, props, toolChatClientFactoryProvider.getIfAvailable(),
                categories, skillsSummary, chatAgentMemory, contextEngineeringService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
    public TravelAgent travelAgent(
            @org.springframework.beans.factory.annotation.Qualifier("travelToolChatClientFactory")
            ObjectProvider<ToolChatClientFactory> travelToolChatClientFactoryProvider,
            ObjectProvider<SkillTools> skillToolsProvider,
            @org.springframework.beans.factory.annotation.Qualifier("travelAgentMemory") AgentMemory travelAgentMemory,
            AgentProperties props,
            ContextEngineeringService contextEngineeringService) {
        ToolChatClientFactory factory = travelToolChatClientFactoryProvider.getIfAvailable();
        if (factory == null) {
            log.info("TravelAgent not created: no travel tools available");
        } else {
            log.info("creating TravelAgent");
        }
        SkillTools skillTools = skillToolsProvider.getIfAvailable();
        String skillsSummary = skillTools != null ? skillTools.getSkillsSummary("TRAVEL") : "";
        return new TravelAgent(factory, skillsSummary, travelAgentMemory,
                Math.max(30, props.getToolCallTimeoutSeconds()), contextEngineeringService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
    public BrowserAgent browserAgent(
            @org.springframework.beans.factory.annotation.Qualifier("browserToolChatClientFactory")
            ObjectProvider<ToolChatClientFactory> browserToolChatClientFactoryProvider,
            ObjectProvider<BrowserTools> browserToolsProvider,
            ObjectProvider<SkillTools> skillToolsProvider,
            @org.springframework.beans.factory.annotation.Qualifier("browserAgentMemory") AgentMemory browserAgentMemory,
            ContextEngineeringService contextEngineeringService) {
        ToolChatClientFactory factory = browserToolChatClientFactoryProvider.getIfAvailable();
        if (factory == null) {
            log.info("BrowserAgent not created: no browser tools available");
        } else {
            log.info("creating BrowserAgent (with self-verification + agent memory)");
        }
        SkillTools skillTools = skillToolsProvider.getIfAvailable();
        String skillsSummary = skillTools != null ? skillTools.getSkillsSummary("BROWSER") : "";
        return new BrowserAgent(factory, browserToolsProvider.getIfAvailable(), skillsSummary,
                browserAgentMemory, contextEngineeringService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression("${agent.ai.enabled:true} && ${agent.ai.image-gen-enabled:false}")
    public ImageGenAgent imageGenAgent(ImageGenClient imageGenClient) {
        log.info("creating ImageGenAgent");
        return new ImageGenAgent(imageGenClient);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.speech", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AudioConverter audioConverter(SpeechProperties props) {
        return new AudioConverter(props.getStt().getFfmpegPath());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.speech", name = {"enabled", "stt.enabled"}, havingValue = "true", matchIfMissing = true)
    public SpeechToTextClient speechToTextClient(SpeechProperties props, AudioConverter audioConverter) {
        log.info("creating FunAsrSttClient: model={}, url={}", props.getStt().getModel(), props.getStt().getApiUrl());
        return new FunAsrSttClient(props, audioConverter);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.speech", name = {"enabled", "tts.enabled"}, havingValue = "true", matchIfMissing = true)
    public TextToSpeechClient textToSpeechClient(SpeechProperties props) {
        log.info("creating Qwen3TtsFlashClient: model={}, voice={}, url={}",
                props.getTts().getModel(), props.getTts().getVoice(), props.getApiUrl());
        return new Qwen3TtsFlashClient(props);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.speech", name = {"enabled", "tts.enabled"}, havingValue = "true", matchIfMissing = true)
    @ConditionalOnBean(VoiceCatalog.class)
    public SpeechAgent speechAgent(TextToSpeechClient ttsClient, VoiceCatalog voiceCatalog) {
        log.info("creating SpeechAgent");
        return new SpeechAgent(ttsClient, voiceCatalog);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AgentRegistry agentRegistry(List<AgentUnit> agentUnits, VoiceCatalog voiceCatalog) {
        log.info("creating AgentRegistry with {} agent units", agentUnits.size());
        return new AgentRegistry(agentUnits, voiceCatalog);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DocumentService documentService(ObjectProvider<SpeechToTextClient> sttClientProvider) {
        log.info("creating DocumentService");
        return new DocumentService(sttClientProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DagPlanningAgent dagPlanningAgent(AgentProperties props, AgentRegistry agentRegistry,
                                             ObjectProvider<SkillTools> skillToolsProvider,
                                             ContextEngineeringService contextEngineeringService) {
        log.info("creating DagPlanningAgentImpl for model={}, url={}",
                props.getIntentModel() != null ? props.getIntentModel() : props.getModel(),
                props.getIntentApiUrl() != null ? props.getIntentApiUrl() : props.getApiUrl());
        return new DagPlanningAgentImpl(
                props, agentRegistry, skillToolsProvider.getIfAvailable(), contextEngineeringService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.orchestrator", name = "dag-enabled",
            havingValue = "true", matchIfMissing = true)
    public DagTaskStore dagTaskStore(DagOrchestrationProperties properties) {
        log.info("creating SQLite DAG task store at {}", properties.getDagStorePath());
        return new SqliteDagTaskStore(properties.getDagStorePath());
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    @ConditionalOnBean({DagPlanningAgent.class, AgentRegistry.class, DagTaskStore.class})
    @ConditionalOnProperty(prefix = "agent.orchestrator", name = "dag-enabled",
            havingValue = "true", matchIfMissing = true)
    public DagOrchestrationService dagOrchestrationService(DagPlanningAgent planningAgent,
                                                           AgentRegistry agentRegistry,
                                                           DagTaskStore taskStore,
                                                           DagOrchestrationProperties properties) {
        log.info("creating DAG orchestration service");
        return new DagOrchestrationService(planningAgent, agentRegistry, taskStore, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SimpleModeRouter simpleModeRouter(AgentRegistry agentRegistry,
                                             ConversationMemory conversationMemory) {
        log.info("creating SimpleModeRouter");
        return new SimpleModeRouter(agentRegistry, conversationMemory);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AgentRegistry.class)
    @ConditionalOnProperty(prefix = "agent.orchestrator", name = "complexity-routing-enabled",
            havingValue = "true", matchIfMissing = true)
    public TaskComplexityRouter taskComplexityRouter(AgentProperties props, AgentRegistry agentRegistry) {
        String model = props.getComplexityModel() != null && !props.getComplexityModel().isBlank()
                ? props.getComplexityModel()
                : (props.getIntentModel() != null && !props.getIntentModel().isBlank()
                ? props.getIntentModel() : props.getModel());
        log.info("creating TaskComplexityRouter model={} timeoutMs={}",
                model, props.getComplexityReadTimeoutMs());
        return new TaskComplexityRouter(
                OpenAiCompatibleClient.forComplexity(props), agentRegistry, model);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
    public MessageRouter messageRouter(ConversationMemory conversationMemory,
                                       DocumentService documentService,
                                       SimpleModeRouter simpleModeRouter,
                                       ObjectProvider<DagOrchestrationService> dagServiceProvider,
                                       ObjectProvider<TaskComplexityRouter> complexityRouterProvider) {
        log.info("creating MessageRouter (simple + DAG mode)");
        return new MessageRouter(conversationMemory, documentService, simpleModeRouter,
                dagServiceProvider.getIfAvailable(), complexityRouterProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(MessageRouter.class)
    public ScheduledTaskExecutor scheduledTaskExecutor(MessageRouter messageRouter,
                                                        ObjectProvider<ILinkClient> ilinkClientProvider,
                                                        ObjectProvider<AudioConverter> audioConverterProvider) {
        return request -> {
            var replies = messageRouter.routeScheduledTask(request);
            ILinkClient client = ilinkClientProvider.getIfAvailable();

            if (replies.isEmpty()) {
                return ScheduledTaskExecutionResult.success("计划任务已执行完成。");
            }

            // Dispatch each reply to the user
            for (var reply : replies) {
                dispatchScheduledReply(client, audioConverterProvider, request.recipientId(), reply);
            }

            // Return the last TEXT reply's content as the result summary
            for (int i = replies.size() - 1; i >= 0; i--) {
                var reply = replies.get(i);
                if (reply.getType() == ModelReply.Type.TEXT || reply.getType() == ModelReply.Type.MIXED) {
                    String text = reply.getTextContent();
                    if (text != null && !text.isBlank()) {
                        return ScheduledTaskExecutionResult.success(text);
                    }
                }
            }
            return ScheduledTaskExecutionResult.success("计划任务已执行完成。");
        };
    }

    private static void dispatchScheduledReply(ILinkClient client,
                                                ObjectProvider<AudioConverter> audioConverterProvider,
                                                String recipientId, ModelReply reply) throws IOException {
        switch (reply.getType()) {
            case TEXT -> {
                String text = reply.getTextContent();
                if (text != null && !text.isBlank() && client != null) {
                    client.sendText(recipientId, text);
                }
            }
            case IMAGE -> {
                if (client == null) return;
                for (var img : reply.getImages()) {
                    log.info("scheduled task dispatching image: name={}, size={}",
                            img.fileName(), img.bytes().length);
                    try {
                        client.sendImage(recipientId, img.bytes(), img.fileName(), null);
                    } catch (Exception e) {
                        log.warn("sendImage failed, falling back to sendFile: {}", e.getMessage());
                        client.sendFile(recipientId, img.bytes(), img.fileName(), null);
                    }
                }
            }
            case MIXED -> {
                if (client == null) return;
                String text = reply.getTextContent();
                if (text != null && !text.isBlank()) {
                    client.sendText(recipientId, text);
                }
                for (var img : reply.getImages()) {
                    try {
                        client.sendImage(recipientId, img.bytes(), img.fileName(), null);
                    } catch (Exception e) {
                        client.sendFile(recipientId, img.bytes(), img.fileName(), null);
                    }
                }
                var filePayload = reply.getFilePayload();
                if (filePayload != null) {
                    client.sendFile(recipientId, filePayload.bytes(), filePayload.fileName(), null);
                }
                var audio = reply.getAudioPayload();
                if (audio != null && audio.bytes() != null && audio.bytes().length > 0) {
                    AudioConverter converter = audioConverterProvider.getIfAvailable();
                    if (converter != null) {
                        byte[] mp3Bytes = converter.wavToMp3(audio.bytes());
                        client.sendFile(recipientId, mp3Bytes, "tts.mp3", null);
                    }
                }
            }
            case VOICE -> {
                var audio = reply.getAudioPayload();
                if (audio == null || audio.bytes() == null || audio.bytes().length == 0) return;
                if (client == null) return;
                AudioConverter converter = audioConverterProvider.getIfAvailable();
                if (converter != null) {
                    byte[] mp3Bytes = converter.wavToMp3(audio.bytes());
                    client.sendFile(recipientId, mp3Bytes, "tts.mp3", null);
                }
            }
            case FILE -> {
                var filePayload = reply.getFilePayload();
                if (filePayload != null && client != null) {
                    client.sendFile(recipientId, filePayload.bytes(), filePayload.fileName(), null);
                }
            }
        }
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ilink", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnBean(MessageRouter.class)
    public MessageHandler messageHandler(ILinkClient ilinkClient,
                                         MessageBridge messageBridge,
                                         MessageRouter messageRouter,
                                         ObjectProvider<SpeechToTextClient> sttClientProvider,
                                         ObjectProvider<AudioConverter> audioConverterProvider,
                                         DocumentService documentService,
                                         ObjectProvider<AutomationRuntime> automationRuntimeProvider,
                                         ObjectProvider<RagStore> ragStoreProvider,
                                         ConversationMemory conversationMemory) {
        log.info("creating MessageHandler");
        return new MessageHandler(ilinkClient, messageBridge, messageRouter,
                sttClientProvider.getIfAvailable(), audioConverterProvider.getIfAvailable(), documentService,
                automationRuntimeProvider.getIfAvailable(),
                ragStoreProvider.getIfAvailable(),
                conversationMemory);
    }
}
