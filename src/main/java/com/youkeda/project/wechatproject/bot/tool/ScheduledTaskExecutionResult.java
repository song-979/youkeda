package com.youkeda.project.wechatproject.bot.tool;

public record ScheduledTaskExecutionResult(boolean success, String message, String errorMessage) {

    public static ScheduledTaskExecutionResult success(String message) {
        return new ScheduledTaskExecutionResult(true, message, null);
    }

    public static ScheduledTaskExecutionResult failure(String errorMessage) {
        return new ScheduledTaskExecutionResult(false, null, errorMessage);
    }
}
