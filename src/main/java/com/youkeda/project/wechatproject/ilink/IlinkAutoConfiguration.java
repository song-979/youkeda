package com.youkeda.project.wechatproject.ilink;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * iLink 接入层自动配置。
 * <p>
 * Bean 创建顺序：MessageBridge → ILinkClient → IlinkWechatService。
 * 启动完成后由 {@link IlinkClientLifecycle} 自动发起扫码登录。
 */
@Configuration
@EnableConfigurationProperties(IlinkProperties.class)
@ConditionalOnProperty(prefix = "ilink", name = "enabled", havingValue = "true", matchIfMissing = true)
public class IlinkAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IlinkAutoConfiguration.class);

    /** 消息桥接器——先于 ILinkClient 创建，等 Service 就绪后注入 delegate */
    @Bean
    @ConditionalOnMissingBean
    public MessageBridge messageBridge() {
        return new MessageBridge();
    }

    /** ILinkClient——SDK 核心客户端 */
    @Bean
    @ConditionalOnMissingBean
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

    /** IlinkWechatService——接入层核心服务，将 client 收到消息分发给已注册的 MessageSink */
    @Bean
    @ConditionalOnMissingBean
    @DependsOn("ilinkClient")
    public IlinkWechatService ilinkWechatService(ILinkClient client,
                                                  MessageBridge bridge) {
        IlinkWechatService service = new IlinkWechatService(client);
        // 桥接：SDK 的消息回调 → Service 的分发逻辑
        bridge.setDelegate(service::dispatchMessages);
        log.info("iLink service created");
        return service;
    }

    /** 默认自动回复——收到任何消息回复"你好，我是你的ai小助手"。通过 ilink.default-reply-enabled=false 关闭 */
    @Bean
    @ConditionalOnProperty(prefix = "ilink", name = "default-reply-enabled", havingValue = "true", matchIfMissing = true)
    public DefaultReplySink defaultReplySink(IlinkWechatService service) {
        return new DefaultReplySink(service);
    }

    /** 生命周期管理——启动时自动登录，关闭时清理资源 */
    @Bean
    @ConditionalOnMissingBean
    public IlinkClientLifecycle ilinkClientLifecycle(IlinkWechatService service,
                                                      IlinkProperties props) {
        return new IlinkClientLifecycle(service, props);
    }


}
