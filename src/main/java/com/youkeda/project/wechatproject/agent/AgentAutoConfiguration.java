package com.youkeda.project.wechatproject.agent;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.youkeda.project.wechatproject.agent.intent.IntentRecognizer;
import com.youkeda.project.wechatproject.agent.intent.LlmIntentRecognizer;
import com.youkeda.project.wechatproject.agent.intent.RegexIntentRecognizer;
import com.youkeda.project.wechatproject.agent.routing.MessageRouter;
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

/**
 * Agent AI 层自动配置。
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

    // ---- 意图识别 ----

    @Bean
    @ConditionalOnMissingBean(name = "intentRecognizer")
    public IntentRecognizer intentRecognizer(AgentProperties props) {
        // 配置了 intent-model → 使用大模型做意图识别，Regex 作为降级兜底
        if (props.getIntentModel() != null && !props.getIntentModel().isEmpty()) {
            log.info("creating LlmIntentRecognizer for model={}, url={}",
                    props.getIntentModel(),
                    props.getIntentApiUrl() != null ? props.getIntentApiUrl() : props.getApiUrl());
            return new LlmIntentRecognizer(props, new RegexIntentRecognizer());
        }
        // 未配置 → 纯正则兜底
        log.info("creating RegexIntentRecognizer (no intent model configured)");
        return new RegexIntentRecognizer();
    }

    // ---- 路由 ----

    @Bean
    @ConditionalOnMissingBean
    public MessageRouter messageRouter(AiModelClient aiModelClient,
                                       ObjectProvider<ImageGenClient> imageGenClientProvider,
                                       ConversationMemory conversationMemory) {
        log.info("creating MessageRouter");
        return new MessageRouter(aiModelClient, imageGenClientProvider.getIfAvailable(), conversationMemory);
    }

    // ---- AgentSink（入口） ----

    @Bean
    @ConditionalOnMissingBean
    @DependsOn("ilinkClient")
    public AgentSink agentSink(ILinkClient ilinkClient,
                               MessageBridge messageBridge,
                               IntentRecognizer intentRecognizer,
                               MessageRouter messageRouter) {
        log.info("creating AgentSink");
        return new AgentSink(ilinkClient, messageBridge, intentRecognizer, messageRouter);
    }
}
