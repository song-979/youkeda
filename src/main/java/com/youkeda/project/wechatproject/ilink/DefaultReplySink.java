package com.youkeda.project.wechatproject.ilink;

import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

/**
 * 默认消息处理器——收到任何消息自动回复"你好，我是你的ai小助手"。
 * <p>
 * 后续替换为真正的 agent 时，只需实现 {@link MessageSink} 并注册为 Spring Bean 即可，
 * 本类可删除或通过 {@code ilink.default-reply-enabled=false} 禁用。
 */
public class DefaultReplySink implements MessageSink, InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DefaultReplySink.class);

    static final String DEFAULT_REPLY = "你好，我是你的ai小助手";

    private final IlinkWechatService service;

    public DefaultReplySink(IlinkWechatService service) {
        this.service = service;
    }

    @Override
    public void afterPropertiesSet() {
        service.addSink(this);
        log.info("default reply sink registered");
    }

    @Override
    public void onMessage(WeixinMessage msg) {
        if (msg.getFrom_user_id() == null) return;
        try {
            service.sendText(msg.getFrom_user_id(), DEFAULT_REPLY);
            log.info("auto-replied to user={}", msg.getFrom_user_id());
        } catch (Exception e) {
            log.error("auto-reply failed for user={}", msg.getFrom_user_id(), e);
        }
    }
}
