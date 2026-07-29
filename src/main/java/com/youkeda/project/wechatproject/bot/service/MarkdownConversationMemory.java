package com.youkeda.project.wechatproject.bot.service;

/**
 * Backward-compatible name for the unified OpenClaw-style Markdown memory.
 *
 * <p>New code should depend on {@link OpenClawConversationMemory}. This class remains so older
 * configuration, tests, or external code that instantiate {@code MarkdownConversationMemory}
 * keep the same constructor surface without carrying a second memory implementation.
 */
@Deprecated(since = "0.0.1", forRemoval = false)
public class MarkdownConversationMemory extends OpenClawConversationMemory {

    public MarkdownConversationMemory(int maxHistoryRounds, int memoryTtlMinutes,
                                      String basePath, int dailyRetentionDays) {
        super(maxHistoryRounds, memoryTtlMinutes, basePath, dailyRetentionDays);
    }
}
