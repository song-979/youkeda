package com.youkeda.project.wechatproject.agent;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.TextItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.youkeda.project.wechatproject.ilink.MessageBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Agent 消息处理器。
 * 接收微信消息（文本/图片） → 调用 AI → 自动回复。
 * <p>
 * 启动时自动注册到 {@link MessageBridge} 的消息分发管线。
 */
public class AgentSink implements OnMessageListener, InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(AgentSink.class);

    private static final int MESSAGE_TYPE_TEXT = 1;
    private static final int MESSAGE_TYPE_IMAGE = 2;
    private static final int MESSAGE_TYPE_VOICE = 3;
    private static final int MESSAGE_TYPE_FILE = 4;
    private static final int MESSAGE_TYPE_VIDEO = 5;

    private final AiModelClient aiModelClient;
    private final ILinkClient ilinkClient;
    private final MessageBridge messageBridge;

    public AgentSink(AiModelClient aiModelClient, ILinkClient ilinkClient, MessageBridge messageBridge) {
        this.aiModelClient = aiModelClient;
        this.ilinkClient = ilinkClient;
        this.messageBridge = messageBridge;
    }

    // ==================== 自动注册 ====================

    @Override
    public void afterPropertiesSet() {
        messageBridge.addListener(this);
        log.info("agent sink registered to message bridge");
    }

    // ==================== 消息处理 ====================

    @Override
    public void onMessages(List<WeixinMessage> messages) {
        for (WeixinMessage msg : messages) {
            handleMessage(msg);
        }
    }

    private void handleMessage(WeixinMessage msg) {
        String fromUserId = msg.getFrom_user_id();
        if (fromUserId == null) {
            log.debug("ignoring message without from_user_id");
            return;
        }

        List<MessageItem> items = msg.getItem_list();
        if (items == null || items.isEmpty()) {
            return;
        }

        // 1. 提取文本
        String text = extractText(items);

        // 2. 下载图片（转 base64 data URI）
        List<String> imageBase64Urls = downloadImages(items);

        // 3. 既无文本也无图片 → 不支持的消息类型
        boolean hasText = !text.isEmpty();
        boolean hasImages = !imageBase64Urls.isEmpty();
        if (!hasText) {
            if (hasImages) {
                text = "请描述一下这张图片";
            } else {
                replyNotSupported(fromUserId);
                return;
            }
        }

        // 4. 调用 AI
        log.info("processing msg from user={}, textLen={}, images={}", fromUserId, text.length(), imageBase64Urls.size());
        try {
            String aiReply = aiModelClient.chat(text, imageBase64Urls);
            ilinkClient.sendText(fromUserId, aiReply);
            log.info("ai reply sent to user={}, len={}", fromUserId, aiReply.length());
        } catch (IOException e) {
            log.error("ai call failed for user={}", fromUserId, e);
            sendErrorReply(fromUserId, e.getMessage());
        } catch (Exception e) {
            log.error("unexpected error for user={}", fromUserId, e);
            sendErrorReply(fromUserId, null);
        }
    }

    // ==================== 文 本 提 取 ====================

    private static String extractText(List<MessageItem> items) {
        return items.stream()
                .filter(item -> item.getType() == MESSAGE_TYPE_TEXT)
                .map(MessageItem::getText_item)
                .filter(Objects::nonNull)
                .map(TextItem::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining());
    }

    // ==================== 图 片 下 载 ====================

    private List<String> downloadImages(List<MessageItem> items) {
        List<String> uris = new ArrayList<>();
        for (MessageItem item : items) {
            if (item.getType() != MESSAGE_TYPE_IMAGE) continue;
            try {
                byte[] bytes = ilinkClient.downloadImageFromMessageItem(item);
                if (bytes != null && bytes.length > 0) {
                    String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
                    uris.add(dataUri);
                    log.debug("downloaded image: {} bytes", bytes.length);
                }
            } catch (Exception e) {
                log.error("failed to download image from message item", e);
            }
        }
        return uris;
    }

    // ==================== 错 误 回 复 ====================

    private void replyNotSupported(String toUserId) {
        try {
            ilinkClient.sendText(toUserId, "目前只支持文字和图片消息，请发送文字或图片给我吧~");
        } catch (IOException e) {
            log.error("failed to send not-supported hint to user={}", toUserId, e);
        }
    }

    private void sendErrorReply(String toUserId, String detail) {
        String reply = detail != null
                ? "抱歉，AI 服务返回错误: " + detail + "\n请稍后再试。"
                : "抱歉，处理消息时发生错误，请稍后再试。";
        try {
            ilinkClient.sendText(toUserId, reply);
        } catch (IOException e) {
            log.error("failed to send error fallback to user={}", toUserId, e);
        }
    }
}
