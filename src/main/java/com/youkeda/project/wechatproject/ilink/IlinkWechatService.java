package com.youkeda.project.wechatproject.ilink;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.*;
import com.github.wechat.ilink.sdk.core.state.ConnectionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 微信 iLink 接入层核心服务。
 * <p>
 * 职责：
 * <ul>
 *   <li>管理 {@link ILinkClient} 生命周期（登录、心跳、断线重连）</li>
 *   <li>将 SDK 的 {@link WeixinMessage} 通过 {@link MessageSink} 分发给下游 agent</li>
 *   <li>提供发送文本/图片/文件/语音/视频等能力的统一入口</li>
 * </ul>
 */
public class IlinkWechatService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IlinkWechatService.class);

    private final ILinkClient client;
    private final List<MessageSink> sinks = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> qrCodeContent = new AtomicReference<>();

    public IlinkWechatService(ILinkClient client) {
        this.client = client;
    }

    // ==================== MessageSink 管理 ====================

    public void addSink(MessageSink sink) {
        sinks.add(sink);
    }

    public void removeSink(MessageSink sink) {
        sinks.remove(sink);
    }

    // ==================== 登录 ====================

    public String login() {
        String qrcode = client.executeLogin();
        qrCodeContent.set(qrcode);

        String preview = qrcode == null ? null
                : (qrcode.length() > 120 ? qrcode.substring(0, 120) + "..." : qrcode);
        log.info("iLink QR code content obtained. preview: {}", preview);
        log.info("iLink login polling token: {}", client.getQrcode());
        return qrcode;
    }

    public String getQrCodeContent() {
        return qrCodeContent.get();
    }

    public String getQrCodeImage() {
        return getQrCodeContent();
    }

    public boolean isLoggedIn() {
        return client.isLoggedIn();
    }

    public LoginContext getLoginContext() {
        return client.getLoginContext();
    }

    public ConnectionStatus getConnectionStatus() {
        return client.getConnectionStatus();
    }

    // ==================== 消息分发 ====================

    /**
     * 将 SDK 原始消息直接分发给所有 MessageSink。
     * agent 层拿到 {@link WeixinMessage} 后，
     * 可通过 {@link #getClient()}.downloadXxxFromMessageItem() 下载媒体数据。
     */
    void dispatchMessages(List<WeixinMessage> sdkMessages) {
        if (sdkMessages == null || sdkMessages.isEmpty()) return;
        for (WeixinMessage msg : sdkMessages) {
            log.debug("dispatching message: type={}, from={}", msg.getMessage_type(), msg.getFrom_user_id());
            for (MessageSink sink : sinks) {
                try {
                    sink.onMessage(msg);
                } catch (Exception e) {
                    log.error("sink error for message from={}", msg.getFrom_user_id(), e);
                }
            }
        }
    }

    // ==================== 发送消息（供 agent 层调用） ====================

    public void sendText(String toUserId, String text) throws IOException {
        client.sendText(toUserId, text);
    }

    public void sendImage(String toUserId, byte[] imageBytes, String fileName) throws IOException {
        client.sendImage(toUserId, imageBytes, fileName, null);
    }

    public void sendImage(String toUserId, byte[] imageBytes, String fileName, String caption) throws IOException {
        client.sendImage(toUserId, imageBytes, fileName, caption);
    }

    public void sendFile(String toUserId, byte[] fileBytes, String fileName) throws IOException {
        client.sendFile(toUserId, fileBytes, fileName, null);
    }

    public void sendVoice(String toUserId, byte[] voiceBytes, String fileName,
                          Integer playTimeMs, Integer sampleRate) throws IOException {
        client.sendVoice(toUserId, voiceBytes, fileName, playTimeMs, sampleRate);
    }

    public void sendVideo(String toUserId, byte[] videoBytes, String fileName,
                          Integer playLengthMs) throws IOException {
        client.sendVideo(toUserId, videoBytes, fileName, playLengthMs, null);
    }

    public void startTyping(String toUserId) throws IOException {
        client.startTyping(toUserId);
    }

    public void stopTyping(String toUserId) throws IOException {
        client.stopTyping(toUserId);
    }

    // ==================== 状态持久化 ====================

    public ResumeContext exportResumeContext() {
        return client.exportResumeContext();
    }

    // ==================== 底层 SDK Client ====================

    /**
     * 获取底层 {@link ILinkClient}，供 agent 下载媒体或高级场景使用。
     * <p>
     * 常用媒体下载方法：
     * <ul>
     *   <li>{@link ILinkClient#downloadImageFromMessageItem(MessageItem)}</li>
     *   <li>{@link ILinkClient#downloadFileFromMessageItem(MessageItem)}</li>
     *   <li>{@link ILinkClient#downloadVoiceFromMessageItem(MessageItem)}</li>
     *   <li>{@link ILinkClient#downloadVideoFromMessageItem(MessageItem)}</li>
     * </ul>
     */
    public ILinkClient getClient() {
        return client;
    }

    // ==================== 生命周期 ====================

    @Override
    public void close() {
        log.info("shutting down IlinkWechatService");
        sinks.clear();
        client.close();
    }
}
