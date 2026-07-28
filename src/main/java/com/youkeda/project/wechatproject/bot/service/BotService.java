package com.youkeda.project.wechatproject.bot.service;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * WeChat iLink service namespace for connection properties, lifecycle, and message fan-out.
 */
public final class BotService {

    private BotService() {
    }

    @ConfigurationProperties(prefix = "ilink")
    public static class IlinkProperties {

        private boolean enabled;
        private boolean autoLogin;
        private long loginTimeoutMs;
        private long heartbeatIntervalMs;
        private boolean heartbeatEnabled;
        private long connectTimeoutMs;
        private long readTimeoutMs;
        private long writeTimeoutMs;
        private int httpMaxRetries;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public boolean isAutoLogin() { return autoLogin; }
        public void setAutoLogin(boolean autoLogin) { this.autoLogin = autoLogin; }

        public long getLoginTimeoutMs() { return loginTimeoutMs; }
        public void setLoginTimeoutMs(long loginTimeoutMs) { this.loginTimeoutMs = loginTimeoutMs; }

        public long getHeartbeatIntervalMs() { return heartbeatIntervalMs; }
        public void setHeartbeatIntervalMs(long heartbeatIntervalMs) { this.heartbeatIntervalMs = heartbeatIntervalMs; }

        public boolean isHeartbeatEnabled() { return heartbeatEnabled; }
        public void setHeartbeatEnabled(boolean heartbeatEnabled) { this.heartbeatEnabled = heartbeatEnabled; }

        public long getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(long connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }

        public long getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(long readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }

        public long getWriteTimeoutMs() { return writeTimeoutMs; }
        public void setWriteTimeoutMs(long writeTimeoutMs) { this.writeTimeoutMs = writeTimeoutMs; }

        public int getHttpMaxRetries() { return httpMaxRetries; }
        public void setHttpMaxRetries(int httpMaxRetries) { this.httpMaxRetries = httpMaxRetries; }
    }

    public static class MessageBridge implements OnMessageListener {

        private static final Logger log = LoggerFactory.getLogger(MessageBridge.class);

        private final List<OnMessageListener> listeners = new CopyOnWriteArrayList<>();
        private volatile String qrcode;

        public void addListener(OnMessageListener listener) {
            listeners.add(listener);
        }

        public void removeListener(OnMessageListener listener) {
            listeners.remove(listener);
        }

        @Override
        public void onMessages(List<WeixinMessage> messages) {
            if (messages == null || messages.isEmpty()) {
                return;
            }
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

    public static class IlinkClientLifecycle implements DisposableBean {

        private static final Logger log = LoggerFactory.getLogger(IlinkClientLifecycle.class);

        private final ILinkClient ilinkClient;
        private final MessageBridge messageBridge;
        private final IlinkProperties props;

        public IlinkClientLifecycle(ILinkClient ilinkClient, MessageBridge messageBridge,
                                    IlinkProperties props) {
            this.ilinkClient = ilinkClient;
            this.messageBridge = messageBridge;
            this.props = props;
        }

        @EventListener(ApplicationReadyEvent.class)
        public void onReady() {
            if (!props.isAutoLogin()) {
                log.info("iLink auto-login disabled (ilink.auto-login=false)");
                return;
            }
            log.info("starting iLink login...");
            try {
                String qrcode = messageBridge.login(ilinkClient);
                String qrPreview = qrcode != null && qrcode.length() > 80
                        ? qrcode.substring(0, 80) + "..." : qrcode;
                log.info("iLink QR code obtained ({} chars), preview: {}", qrcode != null ? qrcode.length() : 0, qrPreview);
                System.out.println("\n========================================");
                System.out.println("  请浏览器打开以下地址扫码登录微信:");
                System.out.println("  http://localhost:8080/ilink/qrcode");
                System.out.println("========================================\n");
            } catch (Exception e) {
                log.error("iLink auto-login failed on startup", e);
            }
        }

        @Override
        public void destroy() {
            ilinkClient.close();
        }
    }
}
