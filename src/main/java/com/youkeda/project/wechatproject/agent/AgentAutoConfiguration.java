package com.youkeda.project.wechatproject.agent;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.youkeda.project.wechatproject.ilink.MessageBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    @Bean
    @ConditionalOnMissingBean
    public AiModelClient aiModelClient(AgentProperties props) {
        log.info("creating OpenAiCompatibleClient for model={}, url={}", props.getModel(), props.getApiUrl());
        return new OpenAiCompatibleClient(props);
    }

    @Bean
    @ConditionalOnMissingBean
    @DependsOn("ilinkClient")
    public AgentSink agentSink(AiModelClient aiModelClient,
                               ILinkClient ilinkClient,
                               MessageBridge messageBridge) {
        log.info("creating AgentSink");
        return new AgentSink(aiModelClient, ilinkClient, messageBridge);
    }
}
