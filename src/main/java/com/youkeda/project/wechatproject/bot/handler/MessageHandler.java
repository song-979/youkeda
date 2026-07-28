package com.youkeda.project.wechatproject.bot.handler;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.model.FileItem;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.TextItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.youkeda.project.wechatproject.bot.service.BotService.MessageBridge;
import com.youkeda.project.wechatproject.bot.service.DocumentService;
import com.youkeda.project.wechatproject.bot.service.DocumentService.ParseResult;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.MessageRouter;
import com.youkeda.project.wechatproject.bot.service.OrchestrationService.ModelReply;
import com.youkeda.project.wechatproject.bot.service.VoiceService.AudioConverter;
import com.youkeda.project.wechatproject.bot.service.VoiceService.SpeechToTextClient;
import com.youkeda.project.wechatproject.bot.tool.AutomationRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
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

public class MessageHandler implements OnMessageListener, InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(MessageHandler.class);

    private static final int MESSAGE_TYPE_TEXT = 1;
    private static final int MESSAGE_TYPE_IMAGE = 2;
    private static final int MESSAGE_TYPE_VOICE = 3;
    private static final int MESSAGE_TYPE_FILE = 4;

    private static final int MAX_IMAGE_DIMENSION = 1024;
    private static final float JPEG_QUALITY = 0.8f;
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    private final ILinkClient ilinkClient;
    private final MessageBridge messageBridge;
    private final MessageRouter router;
    private final SpeechToTextClient sttClient;
    private final AudioConverter audioConverter;
    private final DocumentService documentService;
    private final AutomationRuntime automationRuntime;

    public MessageHandler(ILinkClient ilinkClient,
                          MessageBridge messageBridge,
                          MessageRouter router,
                          SpeechToTextClient sttClient,
                          AudioConverter audioConverter,
                          DocumentService documentService,
                          AutomationRuntime automationRuntime) {
        this.ilinkClient = ilinkClient;
        this.messageBridge = messageBridge;
        this.router = router;
        this.sttClient = sttClient;
        this.audioConverter = audioConverter;
        this.documentService = documentService;
        this.automationRuntime = automationRuntime;
    }

    @Override
    public void afterPropertiesSet() {
        messageBridge.addListener(this);
        log.info("message handler registered to message bridge");
    }

    @Override
    public void onMessages(List<WeixinMessage> messages) {
        for (WeixinMessage msg : messages) {
            handleMessage(msg);
        }
    }

    private void handleMessage(WeixinMessage msg) {
        String fromUserId = msg.getFrom_user_id();
        if (fromUserId == null || fromUserId.isBlank()) {
            log.debug("ignoring message without from_user_id");
            return;
        }

        List<MessageItem> items = msg.getItem_list();
        if (items == null || items.isEmpty()) {
            return;
        }

        String text = extractText(items);
        List<String> imageBase64Urls = downloadImages(items);

        ParseResult fileResult = parseFiles(items);
        if (fileResult != null) {
            text = annotateFileContent(fileResult, text);
            List<String> fileImageUrls = compressFileImages(fileResult.images());
            List<String> combinedImages = new ArrayList<>(fileImageUrls);
            combinedImages.addAll(imageBase64Urls);
            imageBase64Urls = combinedImages;
        }

        if (text.isBlank()) {
            if (!imageBase64Urls.isEmpty()) {
                text = "【用户发送了图片，但未说明要做什么。请根据图片内容询问用户需求。】";
            } else {
                String voiceText = extractVoiceText(items);
                if (voiceText != null && !voiceText.isBlank()) {
                    text = "【用户语音消息】\n语音识别结果：\n" + voiceText;
                } else {
                    replyNotSupported(fromUserId);
                    return;
                }
            }
        }

        try {
            ModelReply reply = router.route(fromUserId, text, imageBase64Urls);
            dispatch(fromUserId, reply);
            log.info("reply dispatched to user={}, type={}", fromUserId, reply.getType());
        } catch (IOException e) {
            log.error("route failed for user={}", fromUserId, e);
            sendErrorReply(fromUserId, e.getMessage());
        } catch (Exception e) {
            log.error("unexpected error for user={}", fromUserId, e);
            sendErrorReply(fromUserId, null);
        } finally {
            if (automationRuntime != null) {
                try {
                    automationRuntime.retryOverduePendingReminders(fromUserId);
                } catch (Exception ignored) {
                    log.debug("failed to retry overdue pending reminders", ignored);
                }
            }
        }
    }

    private void dispatch(String toUser, ModelReply reply) throws IOException {
        switch (reply.getType()) {
            case TEXT -> {
                trySendProgress(toUser, "正在思考...");
                ilinkClient.sendText(toUser, reply.getTextContent());
            }
            case IMAGE -> {
                trySendProgress(toUser, "正在生成图片，请稍候...");
                for (ModelReply.ImagePayload img : reply.getImages()) {
                    sendImageWithFallback(toUser, img);
                }
            }
            case MIXED -> {
                trySendProgress(toUser, mixedProgressMessage(reply));
                if (reply.getTextContent() != null && !reply.getTextContent().isBlank()) {
                    ilinkClient.sendText(toUser, reply.getTextContent());
                }
                for (ModelReply.ImagePayload img : reply.getImages()) {
                    sendImageWithFallback(toUser, img);
                }
                if (reply.getFilePayload() != null) {
                    ModelReply.FilePayload file = reply.getFilePayload();
                    ilinkClient.sendFile(toUser, file.bytes(), file.fileName(), null);
                }
                if (reply.getAudioPayload() != null) {
                    sendAudioAsFile(toUser, reply.getAudioPayload());
                }
            }
            case VOICE -> {
                trySendProgress(toUser, "正在生成语音，请稍候...");
                sendAudioAsFile(toUser, reply.getAudioPayload());
            }
            case FILE -> {
                trySendProgress(toUser, "正在生成文件，请稍候...");
                ModelReply.FilePayload file = reply.getFilePayload();
                ilinkClient.sendFile(toUser, file.bytes(), file.fileName(), null);
            }
        }
    }

    private void sendAudioAsFile(String toUser, ModelReply.AudioPayload audio) throws IOException {
        if (audio == null) {
            throw new IOException("语音生成结果为空");
        }
        if (audioConverter == null) {
            throw new IOException("语音功能未启用，无法发送语音回复");
        }
        byte[] mp3Bytes = audioConverter.wavToMp3(audio.bytes());
        ilinkClient.sendFile(toUser, mp3Bytes, "tts.mp3", null);
    }

    private static String mixedProgressMessage(ModelReply reply) {
        boolean hasImage = !reply.getImages().isEmpty();
        boolean hasAudio = reply.getAudioPayload() != null;
        boolean hasFile = reply.getFilePayload() != null;

        if (hasImage && hasAudio && hasFile) {
            return "正在生成图片、语音和文件，请稍候...";
        }
        if (hasImage && hasAudio) {
            return "正在生成图片和语音，请稍候...";
        }
        if (hasImage && hasFile) {
            return "正在生成图片和文件，请稍候...";
        }
        if (hasAudio && hasFile) {
            return "正在生成语音和文件，请稍候...";
        }
        if (hasImage) {
            return "正在生成图片，请稍候...";
        }
        if (hasAudio) {
            return "正在生成语音，请稍候...";
        }
        if (hasFile) {
            return "正在生成文件，请稍候...";
        }
        return "正在处理...";
    }

    private void sendImageWithFallback(String toUser, ModelReply.ImagePayload image) {
        try {
            ilinkClient.sendImage(toUser, image.bytes(), image.fileName(), null);
            return;
        } catch (Exception imageError) {
            log.error("failed to send image to user={}, fileName={}, bytes={}",
                    toUser, image.fileName(), image.bytes().length, imageError);
        }

        try {
            ilinkClient.sendText(toUser, "图片已经生成了，但微信图片通道上传失败，先给你发送文件版本。");
        } catch (IOException noticeError) {
            log.debug("failed to send image fallback notice to user={}", toUser, noticeError);
        }

        try {
            ilinkClient.sendFile(toUser, image.bytes(), image.fileName(), null);
        } catch (Exception fileError) {
            log.error("failed to send image file fallback to user={}, fileName={}",
                    toUser, image.fileName(), fileError);
            try {
                ilinkClient.sendText(toUser, "图片已经生成，但上传到微信失败了，请稍后再试一次。");
            } catch (IOException finalNoticeError) {
                log.debug("failed to send final image failure notice to user={}", toUser, finalNoticeError);
            }
        }
    }

    private void trySendProgress(String toUser, String message) {
        try {
            ilinkClient.sendText(toUser, message);
        } catch (IOException e) {
            log.debug("failed to send progress hint to user={}", toUser);
        }
    }

    private static String extractText(List<MessageItem> items) {
        return items.stream()
                .filter(item -> item.getType() == MESSAGE_TYPE_TEXT)
                .map(MessageItem::getText_item)
                .filter(Objects::nonNull)
                .map(TextItem::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining());
    }

    private List<String> downloadImages(List<MessageItem> items) {
        List<String> uris = new ArrayList<>();
        for (MessageItem item : items) {
            if (item.getType() != MESSAGE_TYPE_IMAGE) {
                continue;
            }
            try {
                byte[] raw = ilinkClient.downloadImageFromMessageItem(item);
                if (raw == null || raw.length == 0) {
                    continue;
                }

                byte[] compressed = compressImage(raw);
                String dataUri = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(compressed);
                uris.add(dataUri);
                log.info("image processed: raw={}KB -> compressed={}KB", raw.length / 1024, compressed.length / 1024);
            } catch (Exception e) {
                log.error("failed to download or compress image", e);
            }
        }
        return uris;
    }

    private static byte[] compressImage(byte[] raw) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(raw));
        if (src == null) {
            log.warn("cannot decode image, returning raw bytes");
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
        int newW = Math.max(1, (int) (w * ratio));
        int newH = Math.max(1, (int) (h * ratio));

        Image scaled = src.getScaledInstance(newW, newH, Image.SCALE_SMOOTH);
        BufferedImage result = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        g.drawImage(scaled, 0, 0, null);
        g.dispose();
        return result;
    }

    private String extractVoiceText(List<MessageItem> items) {
        if (sttClient == null) {
            return null;
        }
        for (MessageItem item : items) {
            if (item.getType() != MESSAGE_TYPE_VOICE) {
                continue;
            }

            VoiceItem voiceItem = item.getVoice_item();
            if (voiceItem == null) {
                continue;
            }

            try {
                if (voiceItem.getText() != null && !voiceItem.getText().isBlank()) {
                    String inlineText = voiceItem.getText().trim();
                    log.info("using voice transcript from message metadata: encodeType={}, text={}",
                            voiceItem.getEncode_type(), inlineText);
                    return inlineText;
                }

                byte[] voiceBytes = ilinkClient.downloadVoiceFromMessageItem(item);
                if (voiceBytes == null || voiceBytes.length == 0) {
                    continue;
                }

                String format = voiceFormatOf(voiceItem.getEncode_type());
                log.info("downloaded voice bytes: {}B, encodeType={}, sampleRate={}, playtime={}ms",
                        voiceBytes.length, voiceItem.getEncode_type(),
                        voiceItem.getSample_rate(), voiceItem.getPlaytime());
                String text = sttClient.recognize(voiceBytes, format);
                log.info("STT result: {}", text);
                return text;
            } catch (Exception e) {
                log.error("STT failed", e);
            }
        }
        return null;
    }

    private static String voiceFormatOf(Integer encodeType) {
        if (encodeType == null) {
            return "silk";
        }
        return switch (encodeType) {
            case 1 -> "wav";
            case 5 -> "amr";
            case 6 -> "silk";
            case 7 -> "mp3";
            case 8 -> "ogg";
            default -> "silk";
        };
    }

    private void replyNotSupported(String toUserId) {
        try {
            ilinkClient.sendText(toUserId, "目前支持文本、图片和语音消息，请发文字、图片或语音给我。");
        } catch (IOException e) {
            log.error("failed to send not-supported hint to user={}", toUserId, e);
        }
    }

    private void sendErrorReply(String toUserId, String detail) {
        String reply = detail != null && !detail.isBlank()
                ? "抱歉，AI 服务返回错误：" + detail + "\n请稍后再试。"
                : "抱歉，处理消息时发生错误，请稍后再试。";
        try {
            ilinkClient.sendText(toUserId, reply);
        } catch (IOException e) {
            log.error("failed to send error fallback to user={}", toUserId, e);
        }
    }

    private ParseResult parseFiles(List<MessageItem> items) {
        if (documentService == null || documentService.isEmpty()) {
            return null;
        }
        for (MessageItem item : items) {
            if (item.getType() != MESSAGE_TYPE_FILE) {
                continue;
            }
            FileItem fileItem = item.getFile_item();
            if (fileItem == null) {
                continue;
            }
            String fileName = fileItem.getFile_name();
            if (fileName == null || fileName.isBlank()) {
                log.warn("file message without filename, skipping");
                continue;
            }

            String extension = DocumentService.extractExtension(fileName);
            if (extension == null || !documentService.isSupported(extension)) {
                log.debug("unsupported file extension: .{} for '{}'", extension, fileName);
                continue;
            }

            try {
                long fileLen = parseFileLen(fileItem.getLen());
                if (fileLen > MAX_FILE_SIZE) {
                    log.warn("file too large: '{}' {}B > {}B", fileName, fileLen, MAX_FILE_SIZE);
                    return new ParseResult(
                            "⚠️ 文件解析失败：文件过大（" + (fileLen / 1024 / 1024) + "MB），最大支持 10MB",
                            List.of(), fileName);
                }

                byte[] bytes = ilinkClient.downloadFileFromMessageItem(item);
                if (bytes == null || bytes.length == 0) {
                    log.warn("downloaded file bytes empty: '{}'", fileName);
                    continue;
                }

                if (bytes.length > MAX_FILE_SIZE) {
                    log.warn("downloaded file too large: '{}' {}B", fileName, bytes.length);
                    return new ParseResult(
                            "⚠️ 文件解析失败：文件过大（" + (bytes.length / 1024 / 1024) + "MB），最大支持 10MB",
                            List.of(), fileName);
                }

                return documentService.parse(bytes, fileName);
            } catch (Exception e) {
                log.error("failed to parse file: '{}'", fileName, e);
                return new ParseResult("⚠️ 文件解析失败：" + e.getMessage(), List.of(), fileName);
            }
        }
        return null;
    }

    private static long parseFileLen(String lenStr) {
        if (lenStr == null || lenStr.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(lenStr.trim());
        } catch (NumberFormatException e) {
            log.debug("cannot parse file len string: {}", lenStr);
            return 0;
        }
    }

    private String annotateFileContent(ParseResult fileResult, String userText) {
        StringBuilder annotated = new StringBuilder();
        String ext = DocumentService.extractExtension(fileResult.fileName());
        String typeDesc = fileTypeDescription(ext);
        boolean isAudio = ext != null && (ext.equals("mp3") || ext.equals("wav") || ext.equals("m4a")
                || ext.equals("ogg") || ext.equals("flac") || ext.equals("wma") || ext.equals("aac") || ext.equals("opus"));

        annotated.append("【用户上传文件】").append(fileResult.fileName());
        if (typeDesc != null && !typeDesc.isBlank()) {
            annotated.append("（").append(typeDesc).append("）");
        }

        if (fileResult.text() != null && !fileResult.text().isBlank()) {
            if (isAudio) {
                annotated.append("\n语音识别结果：\n").append(fileResult.text());
            } else {
                annotated.append("\n文档内容：\n").append(fileResult.text());
            }
        } else {
            annotated.append("\n（文件无文字内容）");
        }

        if (!fileResult.images().isEmpty()) {
            annotated.append("\n（文档包含 ").append(fileResult.images().size()).append(" 张嵌入图片，已提取并附带在消息中）");
        }

        if (userText != null && !userText.isBlank()) {
            annotated.append("\n\n---\n【用户消息】\n").append(userText);
        }

        return annotated.toString();
    }

    private static String fileTypeDescription(String extension) {
        if (extension == null) {
            return null;
        }
        return switch (extension) {
            case "docx", "doc" -> "Word文档";
            case "pdf" -> "PDF文档";
            case "txt" -> "纯文本文件";
            case "csv" -> "CSV表格";
            case "json" -> "JSON数据";
            case "xml" -> "XML数据";
            case "md" -> "Markdown";
            case "log" -> "日志文件";
            case "mp3" -> "MP3音频";
            case "wav" -> "WAV音频";
            case "m4a" -> "M4A音频";
            case "ogg" -> "OGG音频";
            case "flac" -> "FLAC音频";
            case "wma" -> "WMA音频";
            case "aac" -> "AAC音频";
            case "opus" -> "Opus音频";
            default -> null;
        };
    }

    private List<String> compressFileImages(List<byte[]> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        List<String> uris = new ArrayList<>();
        for (byte[] raw : images) {
            try {
                byte[] compressed = compressImage(raw);
                String dataUri = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(compressed);
                uris.add(dataUri);
            } catch (Exception e) {
                log.error("failed to compress file image", e);
            }
        }
        return uris;
    }

}
