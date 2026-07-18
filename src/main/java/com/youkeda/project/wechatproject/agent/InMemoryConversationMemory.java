package com.youkeda.project.wechatproject.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的对话记忆实现。
 * <p>
 * 特性：
 * <ul>
 *   <li>ConcurrentHashMap 存用户维度数据，线程安全</li>
 *   <li>每个用户最多 {@code maxHistoryRounds * 2} 条消息，超量时淘汰最旧的</li>
 *   <li>内存 TTL（{@code memoryTtlMinutes}），过期自动清理</li>
 *   <li>重启丢失，适合个人助手场景</li>
 * </ul>
 */
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
        // TTL 检查
        if (isExpired(slot)) {
            store.remove(userId);
            log.debug("history expired for user={}", userId);
            return List.of();
        }
        slot.lastAccess = System.currentTimeMillis();
        return new ArrayList<>(slot.messages);
    }

    @Override
    public void append(String userId, String userMessage, String assistantReply) {
        long now = System.currentTimeMillis();
        UserSlot slot = store.computeIfAbsent(userId, k -> new UserSlot(now));
        slot.lastAccess = now;

        slot.messages.addLast(new ChatRequest.Message("user", userMessage));
        slot.messages.addLast(new ChatRequest.Message("assistant", assistantReply));

        // 淘汰最旧的消息
        while (slot.messages.size() > maxMessages) {
            slot.messages.removeFirst();
        }
    }

    @Override
    public void appendUserMessage(String userId, String userMessage) {
        long now = System.currentTimeMillis();
        UserSlot slot = store.computeIfAbsent(userId, k -> new UserSlot(now));
        slot.lastAccess = now;

        slot.messages.addLast(new ChatRequest.Message("user", userMessage));

        // 淘汰最旧的消息
        while (slot.messages.size() > maxMessages) {
            slot.messages.removeFirst();
        }
    }

    @Override
    public void clear(String userId) {
        store.remove(userId);
        log.debug("history cleared for user={}", userId);
    }

    private boolean isExpired(UserSlot slot) {
        return System.currentTimeMillis() - slot.lastAccess > ttlMillis;
    }

    private static class UserSlot {
        final Deque<ChatRequest.Message> messages = new ArrayDeque<>();
        volatile long lastAccess;

        UserSlot(long lastAccess) {
            this.lastAccess = lastAccess;
        }
    }
}
