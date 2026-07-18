package com.youkeda.project.wechatproject.agent;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.TextItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.youkeda.project.wechatproject.agent.intent.IntentRecognizer;
import com.youkeda.project.wechatproject.agent.intent.IntentResult;
import com.youkeda.project.wechatproject.agent.routing.MessageRouter;
import com.youkeda.project.wechatproject.agent.routing.ModelReply;
import com.youkeda.project.wechatproject.ilink.MessageBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Agent 消息处理器。
 * <p>
 * 职责：
 * <ol>
 *   <li>从微信消息中提取文本、下载并压缩图片</li>
 *   <li>调用 {@link IntentRecognizer} 做意图识别</li>
 *   <li>调用 {@link MessageRouter} 路由到对应模型并获取回复</li>
 *   <li>将 {@link ModelReply} 分发到 iLink 通道发送给用户</li>
 * </ol>
 * <p>
 * 记忆和模型调用逻辑已移至 {@link MessageRouter}，本类不再直接操作。
 */
public class AgentSink implements OnMessageListener, InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(AgentSink.class);

    private static final int MESSAGE_TYPE_TEXT = 1;
    private static final int MESSAGE_TYPE_IMAGE = 2;
    private static final int MESSAGE_TYPE_VOICE = 3;
    private static final int MESSAGE_TYPE_FILE = 4;
    private static final int MESSAGE_TYPE_VIDEO = 5;

    private final ILinkClient ilinkClient;
    private final MessageBridge messageBridge;
    private final IntentRecognizer intentRecognizer;
    private final MessageRouter router;

    public AgentSink(ILinkClient ilinkClient,
                     MessageBridge messageBridge,
                     IntentRecognizer intentRecognizer,
                     MessageRouter router) {
        this.ilinkClient = ilinkClient;
        this.messageBridge = messageBridge;
        this.intentRecognizer = intentRecognizer;
        this.router = router;
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

        // 1. 提取文本 + 下载图片
        String text = extractText(items);
        List<String> imageBase64Urls = downloadImages(items);

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

        // 2. 意图识别（只看文本）
        IntentResult intent = intentRecognizer.recognize(text);

        // 3. 路由执行（router 内部处理 memory + 模型调用）
        try {
            ModelReply reply = router.route(fromUserId, text, imageBase64Urls, intent);
            dispatch(fromUserId, reply);
            log.info("reply dispatched to user={}, type={}", fromUserId, reply.getType());
        } catch (IOException e) {
            log.error("route failed for user={}", fromUserId, e);
            sendErrorReply(fromUserId, e.getMessage());
        } catch (Exception e) {
            log.error("unexpected error for user={}", fromUserId, e);
            sendErrorReply(fromUserId, null);
        }
    }

    // ==================== 分发回复 ====================

    /**
     * 根据 {@link ModelReply} 的类型，通过 iLink 通道发送给用户。
     */
    private void dispatch(String toUser, ModelReply reply) throws IOException {
        switch (reply.getType()) {
            case TEXT -> {
                ilinkClient.sendText(toUser, reply.getTextContent());
            }
            case IMAGE -> {
                // 先生成中提示（异步发送，失败了也无所谓）
                trySendProgress(toUser);
                for (ModelReply.ImagePayload img : reply.getImages()) {
                    ilinkClient.sendImage(toUser, img.bytes(), img.fileName(), null);
                }
            }
            case MIXED -> {
                if (reply.getTextContent() != null && !reply.getTextContent().isEmpty()) {
                    ilinkClient.sendText(toUser, reply.getTextContent());
                }
                for (ModelReply.ImagePayload img : reply.getImages()) {
                    ilinkClient.sendImage(toUser, img.bytes(), img.fileName(), null);
                }
            }
        }
    }

    private void trySendProgress(String toUser) {
        try {
            ilinkClient.sendText(toUser, "正在生成图片，请稍候...");
        } catch (IOException e) {
            log.debug("failed to send progress hint to user={}", toUser);
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

    // ==================== 图 片 下 载 & 压 缩 ====================

    private static final int MAX_IMAGE_DIMENSION = 1024;
    private static final float JPEG_QUALITY = 0.8f;

    private List<String> downloadImages(List<MessageItem> items) {
        List<String> uris = new ArrayList<>();
        for (MessageItem item : items) {
            if (item.getType() != MESSAGE_TYPE_IMAGE) continue;
            try {
                byte[] raw = ilinkClient.downloadImageFromMessageItem(item);
                if (raw == null || raw.length == 0) continue;

                byte[] compressed = compressImage(raw);
                String dataUri = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(compressed);
                uris.add(dataUri);
                log.info("image processed: raw={}KB -> compressed={}KB", raw.length / 1024, compressed.length / 1024);
            } catch (Exception e) {
                log.error("failed to download/compress image", e);
            }
        }
        return uris;
    }

    private static byte[] compressImage(byte[] raw) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(raw));
        if (src == null) {
            log.warn("cannot decode image, sending raw bytes");
            return raw;
        }

        BufferedImage scaled = resizeIfNeeded(src);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (writers.hasNext()) {
            ImageWriter writer = writers.next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(new MemoryCacheImageOutputStream(out));
            writer.write(null, new IIOImage(scaled, null, null), param);
            writer.dispose();
        } else {
            ImageIO.write(scaled, "jpg", out);
        }

        return out.toByteArray();
    }

    private static BufferedImage resizeIfNeeded(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int max = Math.max(w, h);
        if (max <= MAX_IMAGE_DIMENSION) {
            return src;
        }

        double ratio = (double) MAX_IMAGE_DIMENSION / max;
        int newW = (int) (w * ratio);
        int newH = (int) (h * ratio);

        Image scaled = src.getScaledInstance(newW, newH, Image.SCALE_SMOOTH);
        BufferedImage result = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        g.drawImage(scaled, 0, 0, null);
        g.dispose();
        return result;
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
