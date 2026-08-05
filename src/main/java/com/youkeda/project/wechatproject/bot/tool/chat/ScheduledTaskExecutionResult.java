package com.youkeda.project.wechatproject.bot.tool.chat;

import java.time.Instant;

public record ScheduledTaskExecutionResult(
        boolean success,
        String message,
        String errorMessage,
        Instant nextWakeAt,
        boolean alreadyDispatched,
        String nextWakeNote) {

    public static ScheduledTaskExecutionResult success(String message) {
        return new ScheduledTaskExecutionResult(true, message, null, null, false, null);
    }

    public static ScheduledTaskExecutionResult successDispatched(String message) {
        return new ScheduledTaskExecutionResult(true, message, null, null, true, null);
    }

    public static ScheduledTaskExecutionResult reschedule(Instant nextWakeAt, String message) {
        return reschedule(nextWakeAt, message, null);
    }

    public static ScheduledTaskExecutionResult reschedule(
            Instant nextWakeAt, String message, String nextWakeNote) {
        return new ScheduledTaskExecutionResult(
                true, message, null, nextWakeAt, false, nextWakeNote);
    }

    public static ScheduledTaskExecutionResult failure(String errorMessage) {
        return new ScheduledTaskExecutionResult(false, null, errorMessage, null, false, null);
    }
}
