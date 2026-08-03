package com.youkeda.project.wechatproject.bot.tool.automation;

public interface ScheduledTaskExecutor {

    ScheduledTaskExecutionResult execute(ScheduledTaskExecutionRequest request) throws Exception;
}
