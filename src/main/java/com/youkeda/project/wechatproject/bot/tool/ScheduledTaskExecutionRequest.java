package com.youkeda.project.wechatproject.bot.tool;

import java.time.Instant;
import java.util.List;

public record ScheduledTaskExecutionRequest(
        String taskId,
        String recipientId,
        String title,
        String instruction,
        String originalRequest,
        List<String> expectedToolCategories,
        Instant scheduledFor,
        boolean recurring) {

    public ScheduledTaskExecutionRequest {
        expectedToolCategories = expectedToolCategories != null ? List.copyOf(expectedToolCategories) : List.of();
    }
}
