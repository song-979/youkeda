package com.youkeda.project.wechatproject.bot.tool.chat;

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
        boolean recurring,
        AutomationStore.AutomationTaskKind taskKind) {

    public ScheduledTaskExecutionRequest {
        expectedToolCategories = expectedToolCategories != null ? List.copyOf(expectedToolCategories) : List.of();
        taskKind = taskKind != null ? taskKind : AutomationStore.AutomationTaskKind.LLM_TASK;
    }

    public ScheduledTaskExecutionRequest(
            String taskId,
            String recipientId,
            String title,
            String instruction,
            String originalRequest,
            List<String> expectedToolCategories,
            Instant scheduledFor,
            boolean recurring) {
        this(taskId, recipientId, title, instruction, originalRequest, expectedToolCategories,
                scheduledFor, recurring, AutomationStore.AutomationTaskKind.LLM_TASK);
    }

    public boolean heartbeat() {
        return taskKind == AutomationStore.AutomationTaskKind.AGENT_HEARTBEAT;
    }
}
