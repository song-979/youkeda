package com.youkeda.project.wechatproject.bot.memory;

import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;

import java.util.List;

public interface ConversationMemory {
    List<ChatRequest.Message> getHistory(String userId);

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

    /**
     * Get history with semantic retrieval augmented by the current user message.
     * Implementations may use the message to search for relevant past memories.
     */
    default List<ChatRequest.Message> getHistory(String userId, String userMessage) {
        return getHistory(userId);
    }

    /** Save a serialized TaskScratchpad for pause-resume across turns. */
    default void saveScratchpad(String userId, String scratchpadJson) {
    }

    /** Load the saved TaskScratchpad JSON, or null if none. */
    default String loadScratchpad(String userId) {
        return null;
    }

    /** Clear the saved TaskScratchpad. */
    default void clearScratchpad(String userId) {
    }
}
