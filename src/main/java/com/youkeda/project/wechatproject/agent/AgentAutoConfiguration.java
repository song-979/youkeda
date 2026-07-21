package com.youkeda.project.wechatproject.agent;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.youkeda.project.wechatproject.agent.file.AudioFileParser;
import com.youkeda.project.wechatproject.agent.file.FileParser;
import com.youkeda.project.wechatproject.agent.file.FileParserRegistry;
import com.youkeda.project.wechatproject.agent.file.PdfParser;
import com.youkeda.project.wechatproject.agent.file.TxtParser;
import com.youkeda.project.wechatproject.agent.file.WordParser;
import com.youkeda.project.wechatproject.agent.orchestration.AgentRegistry;
import com.youkeda.project.wechatproject.agent.orchestration.AgentUnit;
import com.youkeda.project.wechatproject.agent.orchestration.OrchestratorAgent;
import com.youkeda.project.wechatproject.agent.orchestration.OrchestratorAgentImpl;
import com.youkeda.project.wechatproject.agent.orchestration.ChatAgent;
import com.youkeda.project.wechatproject.agent.orchestration.ImageGenAgent;
import com.youkeda.project.wechatproject.agent.orchestration.SpeechAgent;
import com.youkeda.project.wechatproject.agent.speech.AudioConverter;
import com.youkeda.project.wechatproject.agent.speech.SpeechToTextClient;
import com.youkeda.project.wechatproject.agent.speech.TextToSpeechClient;
import com.youkeda.project.wechatproject.agent.speech.VoiceCatalog;
import com.youkeda.project.wechatproject.ilink.MessageBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import java.util.List;

/**
 * Agent AI 层自动配置（编排版本）。
 * <p>
 * 通过 {@code agent.ai.enabled=false} 可完全禁用 agent 层。
 */
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
@ConditionalOnProperty(prefix = "agent.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AgentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentAutoConfiguration.class);

    // ---- 模型客户端 ----

    @Bean
    @ConditionalOnMissingBean
    public AiModelClient aiModelClient(AgentProperties props) {
        log.info("creating OpenAiCompatibleClient for model={}, url={}", props.getModel(), props.getApiUrl());
        return new OpenAiCompatibleClient(props);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.ai", name = "image-gen-enabled", havingValue = "true")
    public ImageGenClient imageGenClient(AgentProperties props) {
        log.info("creating DashScopeImageGenClient for model={}, url={}",
                props.getImageGenModel(), props.getImageGenApiUrl());
        return new DashScopeImageGenClient(props);
    }

    // ---- 记忆 ----

    @Bean
    @ConditionalOnMissingBean
    public ConversationMemory conversationMemory(AgentProperties props) {
        log.info("creating InMemoryConversationMemory maxRounds={}, ttlMin={}",
                props.getMaxHistoryRounds(), props.getMemoryTtlMinutes());
        return new InMemoryConversationMemory(props.getMaxHistoryRounds(), props.getMemoryTtlMinutes());
    }

    // ---- 子模型 AgentUnit ----

    @Bean
    @ConditionalOnMissingBean
    public ChatAgent chatAgent(AiModelClient aiModelClient) {
        log.info("creating ChatAgent");
        return new ChatAgent(aiModelClient);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent.ai", name = "image-gen-enabled", havingValue = "true")
    public ImageGenAgent imageGenAgent(ImageGenClient imageGenClient) {
        log.info("creating ImageGenAgent");
        return new ImageGenAgent(imageGenClient);
    }

    @Bean
    @ConditionalOnMissingBean
    public SpeechAgent speechAgent(ObjectProvider<TextToSpeechClient> ttsClientProvider,
                                   VoiceCatalog voiceCatalog) {
        TextToSpeechClient ttsClient = ttsClientProvider.getIfAvailable();
        if (ttsClient != null) {
            log.info("creating SpeechAgent");
            return new SpeechAgent(ttsClient, voiceCatalog);
        }
        log.info("SpeechAgent skipped (no TextToSpeechClient available)");
        return null;
    }

    // ---- 子模型注册中心 ----

    @Bean
    @ConditionalOnMissingBean
    public AgentRegistry agentRegistry(List<AgentUnit> agentUnits, VoiceCatalog voiceCatalog) {
        log.info("creating AgentRegistry with {} agent units", agentUnits.size());
        return new AgentRegistry(agentUnits, voiceCatalog);
    }

    // ---- 编排器 ----

    @Bean
    @ConditionalOnMissingBean
    public FileParserRegistry fileParserRegistry(
            ObjectProvider<SpeechToTextClient> sttClientProvider) {
        log.info("creating FileParserRegistry");
        List<FileParser> parsers = new java.util.ArrayList<>();
        parsers.add(new WordParser());
        parsers.add(new PdfParser());
        parsers.add(new TxtParser());
        SpeechToTextClient stt = sttClientProvider.getIfAvailable();
        if (stt != null) {
            parsers.add(new AudioFileParser(stt));
            log.info("AudioFileParser registered (STT available)");
        } else {
            log.info("AudioFileParser skipped (no STT client available)");
        }
        return new FileParserRegistry(parsers);
    }

    @Bean
    @ConditionalOnMissingBean
    public OrchestratorAgent orchestratorAgent(AgentProperties props, AgentRegistry agentRegistry) {
        log.info("creating OrchestratorAgentImpl for model={}, url={}",
                props.getIntentModel() != null ? props.getIntentModel() : props.getModel(),
                props.getIntentApiUrl() != null ? props.getIntentApiUrl() : props.getApiUrl());
        return new OrchestratorAgentImpl(props, agentRegistry);
    }

    // ---- 文件生成 ----

    @Bean
    @ConditionalOnMissingBean
    public FileGenerator fileGenerator() {
        log.info("creating FileGenerator");
        return new FileGenerator();
    }

    // ---- 路由 ----

    @Bean
    @ConditionalOnMissingBean
    public MessageRouter messageRouter(OrchestratorAgent orchestratorAgent,
                                       AgentRegistry agentRegistry,
                                       ConversationMemory conversationMemory,
                                       VoiceCatalog voiceCatalog,
                                       FileGenerator fileGenerator) {
        log.info("creating MessageRouter (orchestration mode)");
        return new MessageRouter(orchestratorAgent, agentRegistry, conversationMemory, voiceCatalog, fileGenerator);
    }

    // ---- AgentSink（入口） ----

    @Bean
    @ConditionalOnMissingBean
    @DependsOn("ilinkClient")
    public AgentSink agentSink(ILinkClient ilinkClient,
                               MessageBridge messageBridge,
                               MessageRouter messageRouter,
                               ObjectProvider<SpeechToTextClient> sttClientProvider,
                               AudioConverter audioConverter,
                               FileParserRegistry fileParserRegistry) {
        log.info("creating AgentSink");
        return new AgentSink(ilinkClient, messageBridge, messageRouter,
                sttClientProvider.getIfAvailable(), audioConverter, fileParserRegistry);
    }
}
