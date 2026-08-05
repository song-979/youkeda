package com.youkeda.project.wechatproject.bot.monitor;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

@Component
public class ChatLogRecorder {

    private static final int MAX_ENTRIES = 120;
    private static final int MAX_CONTENT_LENGTH = 600;

    private final Deque<ChatEntry> entries = new ArrayDeque<>();

    public synchronized void incoming(String userId, String kind, String content) {
        append("incoming", userId, kind, content, "已收到");
    }

    public synchronized void outgoing(String userId, String kind, String content) {
        append("outgoing", userId, kind, content, "已发送");
    }

    public synchronized void failed(String userId, String kind, String content) {
        append("outgoing", userId, kind, content, "失败提示");
    }

    public synchronized List<ChatEntry> recent(int limit) {
        int normalizedLimit = Math.max(1, Math.min(limit, MAX_ENTRIES));
        List<ChatEntry> snapshot = new ArrayList<>(entries);
        int from = Math.max(0, snapshot.size() - normalizedLimit);
        return snapshot.subList(from, snapshot.size()).reversed();
    }

    private void append(String direction, String userId, String kind, String content, String status) {
        entries.addLast(new ChatEntry(
                UUID.randomUUID().toString(),
                Instant.now(),
                direction,
                maskUserId(userId),
                kind,
                limit(content),
                status
        ));
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
    }

    private static String limit(String value) {
        if (value == null || value.isBlank()) {
            return "无文本内容";
        }
        String normalized = value.strip();
        if (normalized.length() <= MAX_CONTENT_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_CONTENT_LENGTH) + "...";
    }

    private static String maskUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return "未知用户";
        }
        if (userId.length() <= 12) {
            return userId.charAt(0) + "***" + userId.charAt(userId.length() - 1);
        }
        return userId.substring(0, 6) + "..." + userId.substring(userId.length() - 6);
    }

    public record ChatEntry(
            String id,
            Instant at,
            String direction,
            String userId,
            String kind,
            String content,
            String status
    ) {
    }
}
