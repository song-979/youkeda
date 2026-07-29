package com.youkeda.project.wechatproject.bot.tool.chat;

public interface ScheduledTaskExecutor {

    ScheduledTaskExecutionResult execute(ScheduledTaskExecutionRequest request) throws Exception;
}
