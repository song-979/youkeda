package com.youkeda.project.wechatproject.bot;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.youkeda.project.wechatproject.bot.handler.MessageHandler;
import com.youkeda.project.wechatproject.bot.service.AiService.AgentProperties;
import com.youkeda.project.wechatproject.bot.service.AiService.AiModelClient;
import com.youkeda.project.wechatproject.bot.service.AiService.DashScopeImageGenClient;
import com.youkeda.project.wechatproject.bot.service.AiService.ImageGenClient;
import com.youkeda.project.wechatproject.bot.service.AiService.OpenAiCompatibleClient;
import com.youkeda.project.wechatproject.bot.service.BotService.IlinkClientLifecycle;
import com.youkeda.project.wechatproject.bot.service.BotService.IlinkProperties;
import com.youkeda.project.wechatproject.bot.service.BotService.MessageBridge;
import com.youkeda.project.wechatproject.bot.service.DocumentService;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.AgentRegistry;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.AgentUnit;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.ChatAgent;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.ConversationMemory;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.ImageGenAgent;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.InMemoryConversationMemory;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.MessageRouter;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.OrchestratorAgent;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.OrchestratorAgentImpl;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.OrchestratorProperties;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.SpeechAgent;
import com.youkeda.project.wechatproject.bot.service.VoiceService.AudioConverter;
import com.youkeda.project.wechatproject.bot.service.VoiceService.FunAsrSttClient;
import com.youkeda.project.wechatproject.bot.service.VoiceService.Qwen3TtsFlashClient;
import com.youkeda.project.wechatproject.bot.service.VoiceService.SpeechProperties;
import com.youkeda.project.wechatproject.bot.service.VoiceService.SpeechToTextClient;
import com.youkeda.project.wechatproject.bot.service.VoiceService.TextToSpeechClient;
import com.youkeda.project.wechatproject.bot.service.VoiceService.VoiceCatalog;
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

import java.util.List;

@Configuration
@EnableConfigurationProperties({
        IlinkProperties.class,
        AgentProperties.class,
        OrchestratorProperties.class,
        SpeechProperties.class
})
public class BotAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BotAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ilink", name = "enabled", havingValue = "true", matchIfMissing = true)
    public MessageBridge messageBridge() {
        return new MessageBridge();
    }

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
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ilink", name = "enabled", havingValue = "true", matchIfMissing = true)
    public IlinkClientLifecycle ilinkClientLifecycle(ILinkClient ilinkClient,
                                                     MessageBridge messageBridge,
                                                     IlinkProperties props) {
        return new IlinkClientLifecycle(ilinkClient, messageBridge, props);
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
    @ConditionalOnExpression("${agent.ai.enabled:true} && ${agent.ai.image-gen-enabled:false}")
    public ImageGenClient imageGenClient(AgentProperties props) {
        log.info("creating DashScopeImageGenClient for model={}, url={}",
                props.getImageGenModel(), props.getImageGenApiUrl());
        return new DashScopeImageGenClient(props);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ConversationMemory conversationMemory(AgentProperties props) {
        log.info("creating InMemoryConversationMemory maxRounds={}, ttlMin={}",
                props.getMaxHistoryRounds(), props.getMemoryTtlMinutes());
        return new InMemoryConversationMemory(props.getMaxHistoryRounds(), props.getMemoryTtlMinutes());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
    public VoiceCatalog voiceCatalog() {
        log.info("creating VoiceCatalog");
        return new VoiceCatalog();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ChatAgent chatAgent(AiModelClient aiModelClient) {
        log.info("creating ChatAgent");
        return new ChatAgent(aiModelClient);
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
    public OrchestratorAgent orchestratorAgent(AgentProperties props, AgentRegistry agentRegistry) {
        log.info("creating OrchestratorAgentImpl for model={}, url={}",
                props.getIntentModel() != null ? props.getIntentModel() : props.getModel(),
                props.getIntentApiUrl() != null ? props.getIntentApiUrl() : props.getApiUrl());
        return new OrchestratorAgentImpl(props, agentRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
    public MessageRouter messageRouter(OrchestratorAgent orchestratorAgent,
                                       AgentRegistry agentRegistry,
                                       ConversationMemory conversationMemory,
                                       VoiceCatalog voiceCatalog,
                                       DocumentService documentService,
                                       OrchestratorProperties orchestratorProperties) {
        log.info("creating MessageRouter (orchestration mode)");
        return new MessageRouter(orchestratorAgent, agentRegistry, conversationMemory, voiceCatalog,
                documentService, orchestratorProperties);
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
                                         DocumentService documentService) {
        log.info("creating MessageHandler");
        return new MessageHandler(ilinkClient, messageBridge, messageRouter,
                sttClientProvider.getIfAvailable(), audioConverterProvider.getIfAvailable(), documentService);
    }
}
