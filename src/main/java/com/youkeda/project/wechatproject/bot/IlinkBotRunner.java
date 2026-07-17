package com.youkeda.project.wechatproject.bot;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.youkeda.project.wechatproject.config.IlinkBotProperties;
import com.youkeda.project.wechatproject.llm.OpenAiCompatibleImageService.GeneratedImage;
import com.youkeda.project.wechatproject.service.BotReplyService;
import com.youkeda.project.wechatproject.service.BotReplyService.ReplyPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class IlinkBotRunner implements ApplicationRunner, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(IlinkBotRunner.class);

    private final IlinkBotProperties botProperties;
    private final BotReplyService botReplyService;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Set<Long> processedMessageIds = ConcurrentHashMap.newKeySet();
    private final ExecutorService replyExecutor = Executors.newFixedThreadPool(4);

    private volatile ILinkClient client;
    private volatile Thread workerThread;

    public IlinkBotRunner(IlinkBotProperties botProperties, BotReplyService botReplyService) {
        this.botProperties = botProperties;
        this.botReplyService = botReplyService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!botProperties.isEnabled()) {
            log.info("iLink bot is disabled by configuration.");
            return;
        }

        workerThread = new Thread(this::runBotLoop, "ilink-bot-runner");
        workerThread.start();
    }

    @SuppressWarnings("unchecked")
    private void runBotLoop() {
        try {
            client = ILinkClient.builder().build();

            String qrCodeContent = client.executeLogin();
            log.info("iLink login started. Render this content as a QR code and scan it with WeChat:");
            log.info(qrCodeContent);

            LoginContext loginContext = (LoginContext) client.getLoginFuture().get();
            log.info("iLink login success, botId={}", loginContext.getBotId());

            while (running.get()) {
                try {
                    List<WeixinMessage> messages = client.getUpdates();
                    handleMessages(messages);
                } catch (Exception pollException) {
                    log.warn("iLink polling iteration failed, will continue.", pollException);
                }

                Thread.sleep(botProperties.getPollIntervalMs());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("iLink bot stopped unexpectedly", e);
        } finally {
            closeClient();
        }
    }

    private void handleMessages(List<WeixinMessage> messages) {
        if (CollectionUtils.isEmpty(messages)) {
            return;
        }

        for (WeixinMessage message : messages) {
            if (message == null || CollectionUtils.isEmpty(message.getItem_list())) {
                continue;
            }

            Long messageId = message.getMessage_id();
            if (messageId != null && !processedMessageIds.add(messageId)) {
                continue;
            }

            String fromUserId = message.getFrom_user_id();
            if (!StringUtils.hasText(fromUserId) || fromUserId.endsWith("@im.bot")) {
                continue;
            }

            log.info("Incoming message fromUserId={}, contextToken={}",
                    fromUserId, message.getContext_token());

            for (MessageItem item : message.getItem_list()) {
                if (item == null) {
                    continue;
                }

                if (item.getText_item() != null) {
                    String receivedText = item.getText_item().getText();
                    log.info("Received text message from {}: {}", fromUserId, receivedText);
                    replyExecutor.submit(() -> handleTextReply(fromUserId, receivedText));
                    continue;
                }

                if (item.getImage_item() != null) {
                    String imageUrl = item.getImage_item().getUrl();
                    log.info("Received image message from {}, imageUrl={}", fromUserId, imageUrl);
                    replyExecutor.submit(() -> handleImageReply(fromUserId, imageUrl, item));
                }
            }
        }
    }

    private void handleImageReply(String fromUserId, String imageUrl, MessageItem item) {
        byte[] imageBytes = null;
        try {
            imageBytes = client.downloadImageFromMessageItem(item);
            log.info("Downloaded image bytes from iLink for {}, size={}", fromUserId,
                    imageBytes == null ? 0 : imageBytes.length);
        } catch (Exception e) {
            log.warn("Failed to download image bytes from iLink for {}", fromUserId, e);
        }

        replyText(fromUserId, botReplyService.generateImageReply(fromUserId, imageUrl, imageBytes));
    }

    private void handleTextReply(String fromUserId, String userText) {
        ReplyPlan replyPlan = botReplyService.planTextReply(userText);
        if (replyPlan.isGenerateImage()) {
            handleGeneratedImageRequest(fromUserId, replyPlan.getImagePrompt());
            return;
        }
        replyText(fromUserId, botReplyService.generateTextReply(fromUserId, userText));
    }

    private void replyText(String fromUserId, String replyContent) {
        try {
            if (botProperties.getTypingDelayMs() > 0L) {
                Thread.sleep(botProperties.getTypingDelayMs());
            }

            client.sendText(fromUserId, replyContent);
            log.info("Replied to {}: {}", fromUserId, replyContent);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception sendException) {
            log.error("Reply failed for {}", fromUserId, sendException);
        }
    }

    private void replyImage(String fromUserId, byte[] imageBytes, String fileName, String caption) {
        try {
            if (botProperties.getTypingDelayMs() > 0L) {
                Thread.sleep(botProperties.getTypingDelayMs());
            }

            client.sendImage(fromUserId, imageBytes, fileName, caption);
            log.info("Replied image to {}, size={}", fromUserId, imageBytes.length);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception sendException) {
            log.error("Image reply failed for {}", fromUserId, sendException);
        }
    }

    private void handleGeneratedImageRequest(String fromUserId, String userText) {
        try {
            GeneratedImage generatedImage = botReplyService.generateRequestedImage(userText);
            if (generatedImage == null || generatedImage.getImageBytes() == null || generatedImage.getImageBytes().length == 0) {
                replyText(fromUserId, botProperties.getFixedReply());
                return;
            }

            String caption = StringUtils.hasText(generatedImage.getRevisedPrompt())
                    ? generatedImage.getRevisedPrompt()
                    : botProperties.getImageReplyCaption();
            replyImage(fromUserId, generatedImage.getImageBytes(), generatedImage.getFileName(), caption);
        } catch (Exception e) {
            log.error("Generated image reply failed for {}", fromUserId, e);
            replyText(fromUserId, botProperties.getFixedReply());
        }
    }

    @Override
    public void destroy() throws Exception {
        running.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread.join(2000L);
        }
        replyExecutor.shutdownNow();
        closeClient();
    }

    private void closeClient() {
        ILinkClient currentClient = client;
        client = null;
        if (currentClient == null) {
            return;
        }

        try {
            currentClient.close();
        } catch (Exception e) {
            log.warn("Failed to close iLink client cleanly", e);
        }
    }
}
