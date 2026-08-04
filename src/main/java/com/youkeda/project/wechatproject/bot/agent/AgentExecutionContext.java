package com.youkeda.project.wechatproject.bot.agent;

import com.youkeda.project.wechatproject.bot.context.ContextAudience;
import com.youkeda.project.wechatproject.bot.context.ContextStage;
import com.youkeda.project.wechatproject.bot.context.ContextTaskState;
import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;

import java.util.List;

/** Typed context envelope created by the router or DAG executor for one agent invocation. */
public record AgentExecutionContext(
        String userId,
        ContextStage stage,
        ContextAudience audience,
        List<ChatRequest.Message> recentHistory,
        ContextTaskState taskState,
        List<String> imageUrls,
        String rememberedImageSummary) {

    public AgentExecutionContext {
        stage = stage != null ? stage : ContextStage.EXECUTE;
        audience = audience != null ? audience : ContextAudience.SUB_AGENT;
        recentHistory = recentHistory != null ? List.copyOf(recentHistory) : List.of();
        taskState = taskState != null ? taskState : ContextTaskState.empty();
        imageUrls = imageUrls != null ? List.copyOf(imageUrls) : List.of();
    }

    public static AgentExecutionContext isolated(String userId, ContextTaskState taskState,
                                                 List<String> imageUrls, String imageSummary) {
        return new AgentExecutionContext(userId, ContextStage.EXECUTE, ContextAudience.SUB_AGENT,
                List.of(), taskState, imageUrls, imageSummary);
    }
}
