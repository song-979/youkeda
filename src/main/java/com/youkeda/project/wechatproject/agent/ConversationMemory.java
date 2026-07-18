package com.youkeda.project.wechatproject.agent;

import java.util.List;

/**
 * 对话记忆抽象。
 * 按用户 ID 存储历史消息，AiModelClient 可用作请求上下文。
 * <p>
 * 默认实现 {@link InMemoryConversationMemory}，可替换为 Redis/DB 实现。
 */
public interface ConversationMemory {

    /**
     * 获取指定用户的历史消息列表。
     * 返回空列表表示无历史（不返回 null）。
     */
    List<ChatRequest.Message> getHistory(String userId);

    /**
     * 追加本轮对话（用户消息 + AI 回复）。
     * 内部负责容量管理（淘汰旧消息）。
     */
    void append(String userId, String userMessage, String assistantReply);

    /**
     * 仅追加用户消息（不追加 assistant 回复）。
     * 用于文生图等不需要记录模型回复的场景。
     */
    void appendUserMessage(String userId, String userMessage);

    /**
     * 清除指定用户的所有历史。
     */
    void clear(String userId);
}
