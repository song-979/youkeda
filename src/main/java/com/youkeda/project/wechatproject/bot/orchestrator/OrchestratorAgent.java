package com.youkeda.project.wechatproject.bot.orchestrator;

import com.youkeda.project.wechatproject.bot.model.UserRequest;

public interface OrchestratorAgent {
    OrchestrationResult plan(UserRequest request);

    OrchestrationResult reflect(TaskScratchpad scratchpad, UserRequest originalRequest);
}
