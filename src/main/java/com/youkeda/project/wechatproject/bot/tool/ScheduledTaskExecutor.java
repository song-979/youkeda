package com.youkeda.project.wechatproject.bot.tool;

public interface ScheduledTaskExecutor {

    ScheduledTaskExecutionResult execute(ScheduledTaskExecutionRequest request) throws Exception;
}
