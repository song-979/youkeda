package com.youkeda.project.wechatproject.memory;

import java.util.List;

public interface ConversationMemory {

    List<MemoryMessage> getHistory(String userId);

    default List<MemoryMessage> getHistory(String userId, String userMessage) {
        return getHistory(userId);
    }

    void append(String userId, String userMessage, String assistantReply);

    void appendUserMessage(String userId, String userMessage);

    void clear(String userId);

    default void rememberImageContext(String userId, List<String> imageBase64Urls, String summary) {
    }

    default List<String> getLatestImageDataUrls(String userId) {
        return List.of();
    }

    default String getLatestImageSummary(String userId) {
        return null;
    }
}
