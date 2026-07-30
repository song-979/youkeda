package com.youkeda.project.wechatproject.bot.memory;

import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryConversationMemory implements ConversationMemory {

    private static final Logger log = LoggerFactory.getLogger(InMemoryConversationMemory.class);

    private final int maxMessages;
    private final long ttlMillis;
    private final Map<String, UserSlot> store = new ConcurrentHashMap<>(64);

    public InMemoryConversationMemory(int maxHistoryRounds, int memoryTtlMinutes) {
        this.maxMessages = maxHistoryRounds * 2;
        this.ttlMillis = memoryTtlMinutes * 60_000L;
    }

    @Override
    public List<ChatRequest.Message> getHistory(String userId) {
        UserSlot slot = store.get(userId);
        if (slot == null) {
            return List.of();
        }
        if (isExpired(slot)) {
            store.remove(userId);
            log.debug("history expired for user={}", userId);
            return List.of();
        }
        synchronized (slot) {
            slot.lastAccess = System.currentTimeMillis();
            return new ArrayList<>(slot.messages);
        }
    }

    @Override
    public void append(String userId, String userMessage, String assistantReply) {
        long now = System.currentTimeMillis();
        UserSlot slot = store.computeIfAbsent(userId, k -> new UserSlot(now));
        synchronized (slot) {
            slot.lastAccess = now;

            slot.messages.addLast(new ChatRequest.Message("user", userMessage));
            slot.messages.addLast(new ChatRequest.Message("assistant", assistantReply));

            while (slot.messages.size() > maxMessages) {
                slot.messages.removeFirst();
            }
        }
    }

    @Override
    public void appendUserMessage(String userId, String userMessage) {
        long now = System.currentTimeMillis();
        UserSlot slot = store.computeIfAbsent(userId, k -> new UserSlot(now));
        synchronized (slot) {
            slot.lastAccess = now;

            slot.messages.addLast(new ChatRequest.Message("user", userMessage));

            while (slot.messages.size() > maxMessages) {
                slot.messages.removeFirst();
            }
        }
    }

    @Override
    public void clear(String userId) {
        store.remove(userId);
        log.debug("history cleared for user={}", userId);
    }

    @Override
    public void rememberImageContext(String userId, List<String> imageBase64Urls, String summary) {
        if (userId == null || userId.isBlank() || imageBase64Urls == null || imageBase64Urls.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        UserSlot slot = store.computeIfAbsent(userId, k -> new UserSlot(now));
        slot.lastAccess = now;
        slot.latestImageDataUrls = List.copyOf(imageBase64Urls);
        slot.latestImageSummary = summary;
    }

    @Override
    public List<String> getLatestImageDataUrls(String userId) {
        UserSlot slot = store.get(userId);
        if (slot == null || isExpired(slot) || slot.latestImageDataUrls == null || slot.latestImageDataUrls.isEmpty()) {
            return List.of();
        }
        slot.lastAccess = System.currentTimeMillis();
        return List.copyOf(slot.latestImageDataUrls);
    }

    @Override
    public String getLatestImageSummary(String userId) {
        UserSlot slot = store.get(userId);
        if (slot == null || isExpired(slot)) {
            return null;
        }
        slot.lastAccess = System.currentTimeMillis();
        return slot.latestImageSummary;
    }

    @Override
    public void saveScratchpad(String userId, String scratchpadJson) {
        if (userId == null || userId.isBlank() || scratchpadJson == null) {
            return;
        }
        UserSlot slot = store.computeIfAbsent(userId, k -> new UserSlot(System.currentTimeMillis()));
        synchronized (slot) {
            slot.scratchpadJson = scratchpadJson;
        }
    }

    @Override
    public String loadScratchpad(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        UserSlot slot = store.get(userId);
        if (slot == null || isExpired(slot)) {
            return null;
        }
        synchronized (slot) {
            return slot.scratchpadJson;
        }
    }

    @Override
    public void clearScratchpad(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        UserSlot slot = store.get(userId);
        if (slot != null) {
            synchronized (slot) {
                slot.scratchpadJson = null;
            }
        }
    }

    private boolean isExpired(UserSlot slot) {
        return System.currentTimeMillis() - slot.lastAccess > ttlMillis;
    }

    private static class UserSlot {
        final Deque<ChatRequest.Message> messages = new ArrayDeque<>();
        volatile List<String> latestImageDataUrls;
        volatile String latestImageSummary;
        volatile String scratchpadJson;
        volatile long lastAccess;

        UserSlot(long lastAccess) {
            this.lastAccess = lastAccess;
        }
    }
}
