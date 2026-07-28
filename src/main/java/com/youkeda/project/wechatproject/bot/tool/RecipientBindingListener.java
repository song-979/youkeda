package com.youkeda.project.wechatproject.bot.tool;

import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.youkeda.project.wechatproject.bot.service.BotService.MessageBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

public class RecipientBindingListener implements OnMessageListener, InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(RecipientBindingListener.class);

    private final AutomationStore store;
    private final Clock clock;
    private final ObjectProvider<MessageBridge> messageBridgeProvider;

    public RecipientBindingListener(AutomationStore store, Clock clock) {
        this(store, clock, null);
    }

    public RecipientBindingListener(AutomationStore store,
                                    Clock clock,
                                    ObjectProvider<MessageBridge> messageBridgeProvider) {
        this.store = store;
        this.clock = clock;
        this.messageBridgeProvider = messageBridgeProvider;
    }

    @Override
    public void afterPropertiesSet() {
        if (messageBridgeProvider == null) {
            return;
        }
        MessageBridge bridge = messageBridgeProvider.getIfAvailable();
        if (bridge != null) {
            bridge.addListener(this);
            log.info("recipient binding listener registered to message bridge");
        }
    }

    @Override
    public void onMessages(List<WeixinMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (WeixinMessage message : messages) {
            bindIfEmpty(message);
        }
    }

    private void bindIfEmpty(WeixinMessage message) {
        if (message == null || message.getFrom_user_id() == null || message.getFrom_user_id().isBlank()) {
            return;
        }
        String recipientId = message.getFrom_user_id().trim();
        AutomationStore.RecipientBinding existing = store.getRecipientBinding().orElse(null);
        if (existing == null) {
            Instant now = clock.instant();
            store.saveRecipientBinding(new AutomationStore.RecipientBinding(recipientId, now, now));
            log.info("automation recipient auto-bound to first sender: {}", recipientId);
            return;
        }
        if (!existing.recipientId().equals(recipientId)) {
            log.warn("automation recipient already bound to {}, ignoring sender {}", existing.recipientId(), recipientId);
        }
    }
}
