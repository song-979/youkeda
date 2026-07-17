package com.youkeda.project.wechatproject.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.wechatproject.config.IlinkBotProperties;
import com.youkeda.project.wechatproject.llm.OpenAiCompatibleChatService;
import com.youkeda.project.wechatproject.llm.OpenAiCompatibleChatService.ChatMessage;
import com.youkeda.project.wechatproject.llm.OpenAiCompatibleImageService;
import com.youkeda.project.wechatproject.llm.OpenAiCompatibleImageService.GeneratedImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BotReplyService {

    private static final Logger log = LoggerFactory.getLogger(BotReplyService.class);
    private static final String ROUTER_SYSTEM_PROMPT =
            "You are a routing model for a WeChat assistant. "
                    + "Return JSON only. "
                    + "If the user clearly asks to create, draw, generate, design, or make a new image, return "
                    + "{\"action\":\"generate_image\",\"image_prompt\":\"...\"}. "
                    + "Otherwise return "
                    + "{\"action\":\"reply_text\"}. "
                    + "Do not add markdown fences or extra text.";

    private final IlinkBotProperties botProperties;
    private final OpenAiCompatibleChatService chatService;
    private final OpenAiCompatibleImageService imageService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, List<ChatMessage>> conversationHistory = new ConcurrentHashMap<String, List<ChatMessage>>();

    public BotReplyService(IlinkBotProperties botProperties,
                           OpenAiCompatibleChatService chatService,
                           OpenAiCompatibleImageService imageService,
                           RestTemplate restTemplate) {
        this.botProperties = botProperties;
        this.chatService = chatService;
        this.imageService = imageService;
        this.restTemplate = restTemplate;
    }

    public ReplyPlan planTextReply(String userText) {
        if (!StringUtils.hasText(userText) || !chatService.isAvailable() || !StringUtils.hasText(chatService.getRoutingModel())) {
            return ReplyPlan.replyText();
        }

        try {
            List<ChatMessage> routerMessages = new ArrayList<ChatMessage>();
            routerMessages.add(new ChatMessage("system", ROUTER_SYSTEM_PROMPT));
            routerMessages.add(new ChatMessage("user", userText.trim()));

            String routerReply = chatService.chat(routerMessages, chatService.getRoutingModel());
            return parseReplyPlan(routerReply, userText);
        } catch (Exception e) {
            log.warn("Routing model failed, fallback to text reply.", e);
            return ReplyPlan.replyText();
        }
    }

    public GeneratedImage generateRequestedImage(String userText) {
        if (!imageService.isAvailable()) {
            return null;
        }
        return imageService.generateImage(userText);
    }

    public String generateTextReply(String userId, String userText) {
        if (!StringUtils.hasText(userText)) {
            return botProperties.getFixedReply();
        }

        if (!chatService.isAvailable()) {
            return botProperties.getFixedReply();
        }

        try {
            List<ChatMessage> messages = buildConversation(userId, userText);
            String reply = chatService.chat(messages);
            rememberAssistantReply(userId, reply);
            return reply;
        } catch (Exception e) {
            log.error("LLM reply failed, fallback to fixed reply.", e);
            return botProperties.getFixedReply();
        }
    }

    public String generateImageReply(String userId, String imageUrl, byte[] imageBytes) {
        if ((imageBytes == null || imageBytes.length == 0) && !StringUtils.hasText(imageUrl)) {
            return botProperties.getImageReply();
        }

        if (!StringUtils.hasText(chatService.getVisionModel())) {
            return botProperties.getImageReply();
        }

        try {
            List<ChatMessage> messages = buildImageConversation(userId, imageUrl, imageBytes);
            String reply = chatService.chat(messages, chatService.getVisionModel());
            rememberAssistantReply(userId, reply);
            return reply;
        } catch (Exception e) {
            log.error("LLM image reply failed, fallback to image reply.", e);
            return botProperties.getImageReply();
        }
    }

    private List<ChatMessage> buildConversation(String userId, String userText) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        if (StringUtils.hasText(chatService.getSystemPrompt())) {
            messages.add(new ChatMessage("system", chatService.getSystemPrompt()));
        }

        List<ChatMessage> history = conversationHistory.computeIfAbsent(userId, key -> new ArrayList<ChatMessage>());
        synchronized (history) {
            messages.addAll(history);
            ChatMessage userMessage = new ChatMessage("user", userText.trim());
            messages.add(userMessage);
            history.add(userMessage);
            trimHistory(history);
        }
        return messages;
    }

    private List<ChatMessage> buildImageConversation(String userId, String imageUrl, byte[] imageBytes) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        if (StringUtils.hasText(chatService.getSystemPrompt())) {
            messages.add(new ChatMessage("system", chatService.getSystemPrompt()));
        }

        List<ChatMessage> history = conversationHistory.computeIfAbsent(userId, key -> new ArrayList<ChatMessage>());
        synchronized (history) {
            messages.addAll(history);

            String prompt = StringUtils.hasText(botProperties.getImagePrompt())
                    ? botProperties.getImagePrompt().trim()
                    : "Please describe the image in Chinese.";

            String modelReadableImageUrl = toModelReadableImageUrl(imageUrl, imageBytes);
            ChatMessage userMessage = ChatMessage.userWithImage(prompt, modelReadableImageUrl);
            messages.add(userMessage);
            history.add(new ChatMessage("user", "[User sent an image]"));
            trimHistory(history);
        }
        return messages;
    }

    private void rememberAssistantReply(String userId, String reply) {
        if (!StringUtils.hasText(reply)) {
            return;
        }

        List<ChatMessage> history = conversationHistory.computeIfAbsent(userId, key -> new ArrayList<ChatMessage>());
        synchronized (history) {
            history.add(new ChatMessage("assistant", reply.trim()));
            trimHistory(history);
        }
    }

    private void trimHistory(List<ChatMessage> history) {
        int maxHistoryMessages = Math.max(2, botProperties.getMaxHistoryMessages());
        while (history.size() > maxHistoryMessages) {
            history.remove(0);
        }
    }

    private String toModelReadableImageUrl(String originalImageUrl, byte[] imageBytes) {
        if (imageBytes != null && imageBytes.length > 0) {
            String mimeType = detectImageMimeType(imageBytes);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            return "data:" + mimeType + ";base64," + base64;
        }

        if (!StringUtils.hasText(originalImageUrl)) {
            return originalImageUrl;
        }

        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    originalImageUrl,
                    HttpMethod.GET,
                    HttpEntity.EMPTY,
                    byte[].class
            );

            byte[] downloadedImageBytes = response.getBody();
            if (downloadedImageBytes == null || downloadedImageBytes.length == 0) {
                return originalImageUrl;
            }

            MediaType contentType = response.getHeaders().getContentType();
            String mimeType = contentType != null ? contentType.toString() : "image/jpeg";
            String base64 = Base64.getEncoder().encodeToString(downloadedImageBytes);
            return "data:" + mimeType + ";base64," + base64;
        } catch (Exception e) {
            log.warn("Failed to download image for model access, fallback to original URL: {}", originalImageUrl, e);
            return originalImageUrl;
        }
    }

    private String detectImageMimeType(byte[] imageBytes) {
        if (imageBytes.length >= 8
                && (imageBytes[0] & 0xFF) == 0x89
                && imageBytes[1] == 0x50
                && imageBytes[2] == 0x4E
                && imageBytes[3] == 0x47) {
            return "image/png";
        }
        if (imageBytes.length >= 3
                && (imageBytes[0] & 0xFF) == 0xFF
                && (imageBytes[1] & 0xFF) == 0xD8
                && (imageBytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (imageBytes.length >= 6
                && imageBytes[0] == 'G'
                && imageBytes[1] == 'I'
                && imageBytes[2] == 'F') {
            return "image/gif";
        }
        if (imageBytes.length >= 12
                && imageBytes[0] == 'R'
                && imageBytes[1] == 'I'
                && imageBytes[2] == 'F'
                && imageBytes[8] == 'W'
                && imageBytes[9] == 'E'
                && imageBytes[10] == 'B'
                && imageBytes[11] == 'P') {
            return "image/webp";
        }
        return "image/jpeg";
    }

    private ReplyPlan parseReplyPlan(String routerReply, String originalUserText) {
        if (!StringUtils.hasText(routerReply)) {
            return ReplyPlan.replyText();
        }

        try {
            JsonNode root = objectMapper.readTree(routerReply);
            String action = readText(root, "action");
            if ("generate_image".equalsIgnoreCase(action)) {
                String imagePrompt = readText(root, "image_prompt");
                if (!StringUtils.hasText(imagePrompt)) {
                    imagePrompt = originalUserText.trim();
                }
                return ReplyPlan.generateImage(imagePrompt);
            }
            return ReplyPlan.replyText();
        } catch (Exception e) {
            log.warn("Could not parse routing model reply: {}", routerReply, e);
            return ReplyPlan.replyText();
        }
    }

    private String readText(JsonNode node, String fieldName) {
        if (node == null || !node.has(fieldName) || node.get(fieldName).isNull()) {
            return null;
        }
        String value = node.get(fieldName).asText(null);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public static class ReplyPlan {

        private final boolean generateImage;
        private final String imagePrompt;

        private ReplyPlan(boolean generateImage, String imagePrompt) {
            this.generateImage = generateImage;
            this.imagePrompt = imagePrompt;
        }

        public static ReplyPlan replyText() {
            return new ReplyPlan(false, null);
        }

        public static ReplyPlan generateImage(String imagePrompt) {
            return new ReplyPlan(true, imagePrompt);
        }

        public boolean isGenerateImage() {
            return generateImage;
        }

        public String getImagePrompt() {
            return imagePrompt;
        }
    }
}
