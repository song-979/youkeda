package com.youkeda.project.wechatproject.ilink;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 消息桥接器——解决 ILinkClient 与 AgentSink 之间的循环依赖，同时作为消息分发中心。
 * <p>
 * ILinkClient 构造时需要 OnMessageListener，而 AgentSink 依赖 ILinkClient 发送回复。
 * MessageBridge 先于 ILinkClient 创建，AgentSink 随后注册到该桥接器。
 */
public class MessageBridge implements OnMessageListener {

    private static final Logger log = LoggerFactory.getLogger(MessageBridge.class);

    private final List<OnMessageListener> listeners = new CopyOnWriteArrayList<>();
    private volatile String qrcode;

    // ==================== 监听器管理 ====================

    public void addListener(OnMessageListener listener) {
        listeners.add(listener);
    }

    public void removeListener(OnMessageListener listener) {
        listeners.remove(listener);
    }

    // ==================== 消息分发 ====================

    @Override
    public void onMessages(List<WeixinMessage> messages) {
        if (messages == null || messages.isEmpty()) return;
        for (WeixinMessage msg : messages) {
            for (OnMessageListener listener : listeners) {
                try {
                    listener.onMessages(List.of(msg));
                } catch (Exception e) {
                    log.error("listener error for message from={}", msg.getFrom_user_id(), e);
                }
            }
        }
    }

    // ==================== 登录 ====================

    public String login(ILinkClient client) {
        String qr = client.executeLogin();
        this.qrcode = qr;
        String preview = qr == null ? null : (qr.length() > 120 ? qr.substring(0, 120) + "..." : qr);
        log.info("iLink QR code obtained. preview: {}", preview);
        log.info("iLink login polling token: {}", client.getQrcode());
        return qr;
    }

    public String getQrcode() {
        return qrcode;
    }
}
