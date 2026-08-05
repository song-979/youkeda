package com.youkeda.project.wechatproject.bot.handler;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.exception.ProtocolException;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.model.FileItem;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.TextItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.youkeda.project.wechatproject.bot.memory.ConversationMemory;
import com.youkeda.project.wechatproject.bot.memory.RagStore;
import com.youkeda.project.wechatproject.bot.service.BotService.MessageBridge;
import com.youkeda.project.wechatproject.bot.service.DocumentService;
import com.youkeda.project.wechatproject.bot.service.DocumentService.ParseResult;
import com.youkeda.project.wechatproject.bot.router.MessageRouter;
import com.youkeda.project.wechatproject.bot.model.ModelReply;
import com.youkeda.project.wechatproject.bot.service.VoiceService.AudioConverter;
import com.youkeda.project.wechatproject.bot.service.VoiceService.SpeechToTextClient;
import com.youkeda.project.wechatproject.bot.tool.chat.AutomationRuntime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class MessageHandler implements OnMessageListener, InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MessageHandler.class);

    private static final int MESSAGE_TYPE_TEXT = 1;
    private static final int MESSAGE_TYPE_IMAGE = 2;
    private static final int MESSAGE_TYPE_VOICE = 3;
    private static final int MESSAGE_TYPE_FILE = 4;

    private static final int MAX_IMAGE_DIMENSION = 1024;
    private static final float JPEG_QUALITY = 0.8f;
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    // iLink message send retry: ProtocolException (ret=-2, prepare failed) needs a
    // longer delay — it's typically session-level (rate limit / token expiry), not a
    // transient network glitch. 3 retries × 3s = 9s window for heartbeat to recover.
    private static final int SEND_MAX_RETRIES = 3;
    private static final long SEND_RETRY_DELAY_MS = 3000;
    // Delay between consecutive image sends to avoid iLink rate limiting.
    private static final long INTER_IMAGE_DELAY_MS = 800;
    // Keep final reply batches below iLink's practical text/rate limits.
    static final int MAX_TEXT_MESSAGE_CODE_POINTS = 1800;
    static final long INTER_MESSAGE_DELAY_MS = 10_000;
    // Wait time for iLink session recovery after a send failure before retrying.
    private static final long SESSION_RECOVERY_DELAY_MS = 15_000;
    private static final long OUTBOX_POLL_INTERVAL_MS = 10_000;
    private static final long OUTBOX_INITIAL_RETRY_MS = 30_000;
    private static final long OUTBOX_MAX_RETRY_MS = 5 * 60_000;

    private final ILinkClient ilinkClient;
    private final MessageBridge messageBridge;
    private final MessageRouter router;
    private final SpeechToTextClient sttClient;
    private final AudioConverter audioConverter;
    private final DocumentService documentService;
    private final AutomationRuntime automationRuntime;
    private final RagStore ragStore;
    private final ConversationMemory conversationMemory;
    private final ExecutorService messageExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService deliveryRetryExecutor =
            Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentHashMap<String, CompletableFuture<Void>> userMessageTails = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<PendingDelivery>> pendingDeliveries =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> deliveryLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> inboundContextEpochs = new ConcurrentHashMap<>();

    public MessageHandler(ILinkClient ilinkClient,
                          MessageBridge messageBridge,
                          MessageRouter router,
                          SpeechToTextClient sttClient,
                          AudioConverter audioConverter,
                          DocumentService documentService,
                          AutomationRuntime automationRuntime,
                          RagStore ragStore,
                          ConversationMemory conversationMemory) {
        this.ilinkClient = ilinkClient;
        this.messageBridge = messageBridge;
        this.router = router;
        this.sttClient = sttClient;
        this.audioConverter = audioConverter;
        this.documentService = documentService;
        this.automationRuntime = automationRuntime;
        this.ragStore = ragStore;
        this.conversationMemory = conversationMemory;
    }

    @Override
    public void afterPropertiesSet() {
        messageBridge.addListener(this);
        deliveryRetryExecutor.scheduleWithFixedDelay(
                this::retryPendingDeliveriesSafely,
                OUTBOX_POLL_INTERVAL_MS, OUTBOX_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("message handler registered to message bridge");
    }

    @Override
    public void onMessages(List<WeixinMessage> messages) {
        for (WeixinMessage msg : messages) {
            String userId = msg != null ? msg.getFrom_user_id() : null;
            if (userId == null || userId.isBlank()) {
                continue;
            }
            router.markUserActivity(userId);
            if (automationRuntime != null) {
                automationRuntime.markUserActivity(userId);
            }
            inboundContextEpochs.merge(userId, 1L, Long::sum);
            userMessageTails.compute(userId, (key, previous) -> {
                CompletableFuture<Void> ready = previous == null
                        ? CompletableFuture.completedFuture(null)
                        : previous.handle((ignored, error) -> null);
                CompletableFuture<Void> next = ready.thenRunAsync(() -> handleMessageSafely(msg), messageExecutor);
                next.whenComplete((ignored, error) -> userMessageTails.remove(key, next));
                return next;
            });
        }
    }

    private void handleMessageSafely(WeixinMessage message) {
        try {
            handleMessage(message);
        } catch (RuntimeException e) {
            log.error("async message handling failed", e);
        }
    }

    @Override
    public void destroy() {
        deliveryRetryExecutor.shutdownNow();
        messageExecutor.shutdown();
        try {
            if (!messageExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                messageExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            messageExecutor.shutdownNow();
        }
        userMessageTails.clear();
        pendingDeliveries.clear();
        deliveryLocks.clear();
        inboundContextEpochs.clear();
    }

    private void handleMessage(WeixinMessage msg) {
        String fromUserId = msg.getFrom_user_id();
        if (fromUserId == null || fromUserId.isBlank()) {
            log.debug("ignoring message without from_user_id");
            return;
        }

        retryPendingDeliveryAfterContextRefresh(fromUserId);

        List<MessageItem> items = msg.getItem_list();
        if (items == null || items.isEmpty()) {
            return;
        }

        String text = extractText(items);
        List<String> imageBase64Urls = downloadImages(items);

        String fileAnnot = null;
        String voiceAnnot = null;
        boolean hasImages = !imageBase64Urls.isEmpty();

        ParseResult fileResult = parseFiles(items);
        if (fileResult != null) {
            indexFileContent(fromUserId, fileResult);
            String ext = DocumentService.extractExtension(fileResult.fileName());
            String typeDesc = fileTypeDescription(ext);
            fileAnnot = MessageAnnotation.fileAnnotation(
                    fileResult.fileName(), typeDesc, fileResult.text(), fileResult.images().size());
            List<String> fileImageUrls = compressFileImages(fileResult.images());
            List<String> combinedImages = new ArrayList<>(fileImageUrls);
            combinedImages.addAll(imageBase64Urls);
            imageBase64Urls = combinedImages;
        }

        if (text == null || text.isBlank()) {
            if (hasImages) {
                // Let MessageAnnotation.build handle IMAGE_ONLY hint
            } else {
                String voiceText = extractVoiceText(items);
                if (voiceText != null && !voiceText.isBlank()) {
                    voiceAnnot = MessageAnnotation.voiceAnnotation(voiceText);
                } else {
                    replyNotSupported(fromUserId);
                    return;
                }
            }
        }

        text = MessageAnnotation.build(text, fileAnnot, voiceAnnot, hasImages && (text == null || text.isBlank()));

        try {
            // Per-node progress is intentionally log-only. It previously exhausted the same
            // iLink conversation context before the final result could be delivered.
            Consumer<String> progressCb = progressText ->
                    log.info("[DAG-PROGRESS] user={} message={}", fromUserId, progressText);
            Consumer<List<ModelReply>> completionCb = replies -> {
                boolean delivered = dispatch(fromUserId, replies);
                if (delivered) {
                    log.info("[DELIVERY] async DAG replies DELIVERED user={} count={}",
                            fromUserId, replies != null ? replies.size() : 0);
                } else {
                    log.warn("[DELIVERY] async DAG replies PENDING_RETRY user={} count={}",
                            fromUserId, replies != null ? replies.size() : 0);
                }
            };
            List<ModelReply> replies = router.route(
                    fromUserId, text, imageBase64Urls, progressCb, completionCb);
            boolean delivered = dispatch(fromUserId, replies);
            log.info("[DELIVERY] synchronous replies {} user={} count={}",
                    delivered ? "DELIVERED" : "PENDING_RETRY", fromUserId, replies.size());
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

    /**
     * Dispatches a list of replies to user, sending each one as a separate message.
     * On first protocol failure, waits for the iLink heartbeat to restore the session
     * and retries once before giving up.
     */
    private boolean dispatch(String toUser, List<ModelReply> replies) {
        if (replies == null || replies.isEmpty()) return true;
        ReentrantLock lock = deliveryLock(toUser);
        lock.lock();
        try {
            long attemptedContextEpoch = contextEpoch(toUser);
            return dispatchLocked(toUser, expandTextReplies(replies), attemptedContextEpoch);
        } finally {
            lock.unlock();
        }
    }

    private boolean dispatchLocked(String toUser, List<ModelReply> deliveryReplies,
                                   long attemptedContextEpoch) {
        int sentCount = dispatchBatch(toUser, deliveryReplies, 0);
        if (sentCount >= deliveryReplies.size()) return true;

        // Recovery: wait for iLink heartbeat to restore session, then retry remaining
        log.info("waiting {}ms for iLink session recovery for user={}, {} of {} replies sent",
                SESSION_RECOVERY_DELAY_MS, toUser, sentCount, deliveryReplies.size());
        sleepQuietly(SESSION_RECOVERY_DELAY_MS);

        log.info("retrying {} remaining replies for user={}",
                deliveryReplies.size() - sentCount, toUser);
        int sent2 = dispatchBatch(toUser, deliveryReplies, sentCount);
        if (sent2 < deliveryReplies.size()) {
            log.warn("retry also failed for user={}, {} of {} replies unsent",
                    toUser, deliveryReplies.size() - sent2, deliveryReplies.size());
            saveUnsentToMemory(toUser, deliveryReplies, sent2);
            enqueuePendingDelivery(toUser,
                    deliveryReplies.subList(sent2, deliveryReplies.size()), attemptedContextEpoch);
            return false;
        }
        return true;
    }

    static List<ModelReply> expandTextReplies(List<ModelReply> replies) {
        if (replies == null || replies.isEmpty()) return List.of();
        List<ModelReply> expanded = new ArrayList<>();
        for (ModelReply reply : replies) {
            if (reply == null) continue;
            String text = reply.getTextContent();
            List<String> chunks = splitTextForDelivery(text);
            if (chunks.size() <= 1) {
                expanded.add(reply);
                continue;
            }

            chunks.forEach(chunk -> expanded.add(ModelReply.text(chunk)));
            if (reply.getType() == ModelReply.Type.MIXED
                    && (!reply.getImages().isEmpty()
                    || reply.getAudioPayload() != null
                    || reply.getFilePayload() != null)) {
                expanded.add(new ModelReply(
                        ModelReply.Type.MIXED, null, reply.getImages(),
                        reply.getAudioPayload(), reply.getFilePayload()));
            }
        }
        return List.copyOf(expanded);
    }

    static List<String> splitTextForDelivery(String text) {
        if (text == null || text.isEmpty()
                || text.codePointCount(0, text.length()) <= MAX_TEXT_MESSAGE_CODE_POINTS) {
            return text == null ? List.of() : List.of(text);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int remaining = text.codePointCount(start, text.length());
            if (remaining <= MAX_TEXT_MESSAGE_CODE_POINTS) {
                chunks.add(text.substring(start));
                break;
            }
            int hardEnd = text.offsetByCodePoints(start, MAX_TEXT_MESSAGE_CODE_POINTS);
            int end = preferredSplitBoundary(text, start, hardEnd);
            chunks.add(text.substring(start, end));
            start = end;
        }
        return List.copyOf(chunks);
    }

    private static int preferredSplitBoundary(String text, int start, int hardEnd) {
        int minimumCodePoints = MAX_TEXT_MESSAGE_CODE_POINTS * 2 / 3;
        int minimum = text.offsetByCodePoints(start, minimumCodePoints);
        for (int index = hardEnd; index > minimum; ) {
            int codePoint = text.codePointBefore(index);
            if (Character.isWhitespace(codePoint) || isSentenceBoundary(codePoint)) {
                return index;
            }
            index -= Character.charCount(codePoint);
        }
        return hardEnd;
    }

    private static boolean isSentenceBoundary(int codePoint) {
        return codePoint == '。' || codePoint == '！' || codePoint == '？'
                || codePoint == '；' || codePoint == '.' || codePoint == '!'
                || codePoint == '?' || codePoint == ';';
    }

    private void enqueuePendingDelivery(String userId, List<ModelReply> replies) {
        enqueuePendingDelivery(userId, replies, contextEpoch(userId));
    }

    private void enqueuePendingDelivery(String userId, List<ModelReply> replies,
                                        long attemptedContextEpoch) {
        if (replies == null || replies.isEmpty()) return;
        List<ModelReply> deliveryReplies = expandTextReplies(replies);
        PendingDelivery pending = new PendingDelivery(
                UUID.randomUUID().toString(), deliveryReplies,
                0, System.currentTimeMillis() + OUTBOX_INITIAL_RETRY_MS,
                attemptedContextEpoch);
        pendingDeliveries.computeIfAbsent(userId, ignored -> new ConcurrentLinkedQueue<>())
                .offer(pending);
        log.warn("[DELIVERY] queued outboxId={} user={} replies={} nextRetryMs={}",
                pending.id, userId, deliveryReplies.size(), OUTBOX_INITIAL_RETRY_MS);
    }

    private void retryPendingDeliveriesSafely() {
        try {
            retryPendingDeliveries();
        } catch (Exception e) {
            log.warn("[DELIVERY] outbox retry sweep failed: {}", e.getMessage(), e);
        }
    }

    private void retryPendingDeliveries() {
        pendingDeliveries.keySet().forEach(userId -> retryPendingDelivery(userId, false));
    }

    private void retryPendingDeliveryAfterContextRefresh(String userId) {
        retryPendingDelivery(userId, true);
    }

    private void retryPendingDelivery(String userId, boolean contextJustRefreshed) {
        ConcurrentLinkedQueue<PendingDelivery> queue = pendingDeliveries.get(userId);
        if (queue == null) return;
        PendingDelivery pending = queue.peek();
        if (pending == null) {
            pendingDeliveries.remove(userId, queue);
            return;
        }

        long currentEpoch = contextEpoch(userId);
        if (currentEpoch <= pending.contextEpoch) {
            return;
        }
        if (!contextJustRefreshed && pending.nextAttemptAt > System.currentTimeMillis()) {
            return;
        }

        ReentrantLock lock = deliveryLock(userId);
        if (!lock.tryLock()) {
            return;
        }
        try {
            log.info("[DELIVERY] retrying outboxId={} after context refresh user={} "
                            + "attempt={} replies={} contextEpoch={}->{}",
                    pending.id, userId, pending.attempts + 1, pending.replies.size(),
                    pending.contextEpoch, currentEpoch);
            int sent = dispatchBatch(userId, pending.replies, 0);
            if (sent >= pending.replies.size()) {
                queue.poll();
                log.info("[DELIVERY] outbox DELIVERED outboxId={} user={} attempts={}",
                        pending.id, userId, pending.attempts + 1);
                if (queue.isEmpty()) pendingDeliveries.remove(userId, queue);
                return;
            }

            pending.replies = List.copyOf(pending.replies.subList(sent, pending.replies.size()));
            pending.attempts++;
            pending.contextEpoch = currentEpoch;
            long retryDelay = Math.min(OUTBOX_MAX_RETRY_MS,
                    OUTBOX_INITIAL_RETRY_MS * (1L << Math.min(pending.attempts, 4)));
            pending.nextAttemptAt = System.currentTimeMillis() + retryDelay;
            log.warn("[DELIVERY] outbox still pending outboxId={} user={} attempts={} "
                            + "remaining={} awaitingNewContext=true",
                    pending.id, userId, pending.attempts, pending.replies.size());
        } finally {
            lock.unlock();
        }
    }

    private ReentrantLock deliveryLock(String userId) {
        return deliveryLocks.computeIfAbsent(userId, ignored -> new ReentrantLock(true));
    }

    private long contextEpoch(String userId) {
        return inboundContextEpochs.getOrDefault(userId, 0L);
    }

    private void sendStandaloneText(String userId, String text) throws IOException {
        ReentrantLock lock = deliveryLock(userId);
        lock.lock();
        try {
            sendWithRetry(() -> ilinkClient.sendText(userId, text), "sendText", userId);
        } finally {
            lock.unlock();
        }
    }

    /** Sends replies starting from {@code startIndex}. Returns new total sent count. */
    private int dispatchBatch(String toUser, List<ModelReply> replies, int startIndex) {
        int sentCount = startIndex;
        for (int i = startIndex; i < replies.size(); i++) {
            if (sentCount > 0) {
                sleepQuietly(INTER_MESSAGE_DELAY_MS);
            }
            boolean ok = dispatchSingle(toUser, replies.get(i));
            if (!ok) {
                break;
            }
            sentCount++;
        }
        return sentCount;
    }

    /** Saves unsent reply text to conversation memory so context isn't lost. */
    private void saveUnsentToMemory(String userId, List<ModelReply> replies, int sentCount) {
        if (conversationMemory == null) return;
        try {
            for (int i = sentCount; i < replies.size(); i++) {
                ModelReply reply = replies.get(i);
                String text = reply.getTextContent();
                if (text != null && !text.isBlank()) {
                    conversationMemory.append(userId, "[未送达的系统回复]", text);
                }
            }
            log.info("saved {} unsent replies to conversation memory for user={}",
                    replies.size() - sentCount, userId);
        } catch (Exception e) {
            log.warn("failed to save unsent replies to memory for user={}", userId, e);
        }
    }

    /**
     * Dispatches a single reply to user. Returns true if at least one send succeeded.
     */
    private boolean dispatchSingle(String toUser, ModelReply reply) {
        boolean anySuccess = false;
        switch (reply.getType()) {
            case TEXT -> {
                try {
                    sendWithRetry(() -> ilinkClient.sendText(toUser, reply.getTextContent()),
                            "sendText", toUser);
                    anySuccess = true;
                } catch (Exception e) {
                    log.error("failed to send TEXT reply to user={}", toUser, e);
                }
            }
            case IMAGE -> {
                for (ModelReply.ImagePayload img : reply.getImages()) {
                    try {
                        sendImageWithFallback(toUser, img);
                        anySuccess = true;
                    } catch (Exception e) {
                        log.error("failed to send IMAGE to user={}", toUser, e);
                    }
                    sleepQuietly(INTER_IMAGE_DELAY_MS);
                }
            }
            case MIXED -> {
                if (reply.getTextContent() != null && !reply.getTextContent().isBlank()) {
                    try {
                        sendWithRetry(() -> ilinkClient.sendText(toUser, reply.getTextContent()),
                                "sendText", toUser);
                        anySuccess = true;
                    } catch (Exception e) {
                        log.error("failed to send MIXED text to user={}", toUser, e);
                    }
                }
                for (ModelReply.ImagePayload img : reply.getImages()) {
                    try {
                        sendImageWithFallback(toUser, img);
                        anySuccess = true;
                    } catch (Exception e) {
                        log.error("failed to send MIXED image to user={}", toUser, e);
                    }
                    sleepQuietly(INTER_IMAGE_DELAY_MS);
                }
                if (reply.getFilePayload() != null) {
                    try {
                        ModelReply.FilePayload file = reply.getFilePayload();
                        sendWithRetry(() -> ilinkClient.sendFile(toUser, file.bytes(), file.fileName(), null),
                                "sendFile", toUser);
                        anySuccess = true;
                    } catch (Exception e) {
                        log.error("failed to send MIXED file to user={}", toUser, e);
                    }
                }
                if (reply.getAudioPayload() != null) {
                    try {
                        sendAudioAsFile(toUser, reply.getAudioPayload());
                        anySuccess = true;
                    } catch (Exception e) {
                        log.error("failed to send MIXED audio to user={}", toUser, e);
                    }
                }
            }
            case VOICE -> {
                try {
                    sendAudioAsFile(toUser, reply.getAudioPayload());
                    anySuccess = true;
                } catch (Exception e) {
                    log.error("failed to send VOICE to user={}", toUser, e);
                }
            }
            case FILE -> {
                try {
                    ModelReply.FilePayload file = reply.getFilePayload();
                    sendWithRetry(() -> ilinkClient.sendFile(toUser, file.bytes(), file.fileName(), null),
                            "sendFile", toUser);
                    anySuccess = true;
                } catch (Exception e) {
                    log.error("failed to send FILE to user={}", toUser, e);
                }
            }
        }
        if (!anySuccess) {
            log.warn("all dispatch paths failed for user={}, type={}", toUser, reply.getType());
            try {
                ilinkClient.sendText(toUser, "抱歉，消息发送失败了，请稍后再试。");
            } catch (Exception ignored) {
                log.debug("even error fallback text failed for user={}", toUser);
            }
        }
        return anySuccess;
    }

    private void sendAudioAsFile(String toUser, ModelReply.AudioPayload audio) throws IOException {
        if (audio == null) {
            throw new IOException("语音生成结果为空");
        }
        if (audioConverter == null) {
            throw new IOException("语音功能未启用，无法发送语音回复");
        }
        byte[] mp3Bytes = audioConverter.wavToMp3(audio.bytes());
        sendWithRetry(() -> ilinkClient.sendFile(toUser, mp3Bytes, "tts.mp3", null),
                "sendAudioFile", toUser);
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
        // Retry the image send first — ProtocolException often resolves after heartbeat
        try {
            sendWithRetry(() -> ilinkClient.sendImage(toUser, image.bytes(), image.fileName(), null),
                    "sendImage", toUser);
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

    /**
     * Retries an iLink send operation on ProtocolException (e.g. ret=-2 prepare failed).
     * Waits between retries to allow the iLink heartbeat to restore the session.
     * Only retries protocol-level errors, not IO or other exceptions.
     */
    private void sendWithRetry(IoSendOp op, String opName, String toUser) throws IOException {
        ProtocolException lastProtocolError = null;
        for (int attempt = 0; attempt < SEND_MAX_RETRIES; attempt++) {
            try {
                op.run();
                return; // success
            } catch (ProtocolException e) {
                lastProtocolError = e;
                if (attempt < SEND_MAX_RETRIES - 1) {
                    log.warn("{} attempt {}/{} failed for user={}, retrying in {}ms: {}",
                            opName, attempt + 1, SEND_MAX_RETRIES, toUser,
                            SEND_RETRY_DELAY_MS, e.getMessage());
                    try {
                        Thread.sleep(SEND_RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("retry interrupted", ie);
                    }
                }
            } catch (IOException e) {
                // Non-protocol IO errors are not retried — rethrow immediately
                throw e;
            }
        }
        if (lastProtocolError != null) {
            throw new IOException(opName + " failed with protocol error: " + lastProtocolError.getMessage(),
                    lastProtocolError);
        }
        throw new IOException(opName + " failed after " + SEND_MAX_RETRIES + " attempts");
    }

    @FunctionalInterface
    private interface IoSendOp {
        void run() throws IOException;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class PendingDelivery {
        private final String id;
        private List<ModelReply> replies;
        private int attempts;
        private long nextAttemptAt;
        private long contextEpoch;

        private PendingDelivery(String id, List<ModelReply> replies,
                                int attempts, long nextAttemptAt, long contextEpoch) {
            this.id = id;
            this.replies = replies;
            this.attempts = attempts;
            this.nextAttemptAt = nextAttemptAt;
            this.contextEpoch = contextEpoch;
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
            sendStandaloneText(toUserId, "目前支持文本、图片和语音消息，请发文字、图片或语音给我。");
        } catch (IOException e) {
            log.error("failed to send not-supported hint to user={}", toUserId, e);
        }
    }

    private void sendErrorReply(String toUserId, String detail) {
        String reply = detail != null && !detail.isBlank()
                ? "抱歉，AI 服务返回错误：" + detail + "\n请稍后再试。"
                : "抱歉，处理消息时发生错误，请稍后再试。";
        try {
            sendStandaloneText(toUserId, reply);
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

    /**
     * Auto-index parsed file content into RagStore for future semantic search.
     * Skips audio files (speech transcripts) and empty/error content.
     */
    private void indexFileContent(String userId, ParseResult fileResult) {
        if (ragStore == null) {
            return;
        }
        if (fileResult == null || fileResult.text() == null || fileResult.text().isBlank()) {
            return;
        }
        // Skip error messages and audio transcripts
        if (fileResult.text().startsWith("⚠️ 文件解析失败")) {
            return;
        }
        String ext = DocumentService.extractExtension(fileResult.fileName());
        if (ext != null && (ext.equals("mp3") || ext.equals("wav") || ext.equals("m4a")
                || ext.equals("ogg") || ext.equals("flac") || ext.equals("wma") || ext.equals("aac") || ext.equals("opus"))) {
            return;
        }
        try {
            ragStore.index(userId, "default", fileResult.fileName(), fileResult.text());
            log.debug("auto-indexed file '{}' to rag for userId={}", fileResult.fileName(), userId);
        } catch (Exception e) {
            log.warn("failed to auto-index file '{}' to rag: {}", fileResult.fileName(), e.getMessage());
        }
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
