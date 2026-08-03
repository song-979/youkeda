package com.youkeda.project.wechatproject.bot.context;

import com.youkeda.project.wechatproject.bot.service.AiService.ChatRequest;

import java.util.List;

public record ContextPackage(
        List<ChatRequest.Message> messages,
        ContextRelevance relevance,
        ContextBudgetReport budgetReport,
        List<ContextCompressionAction> compressionActions) {

    public ContextPackage {
        messages = messages != null ? List.copyOf(messages) : List.of();
        relevance = relevance != null ? relevance : ContextRelevance.NEW_TOPIC;
        budgetReport = budgetReport != null ? budgetReport : new ContextBudgetReport(0, 0, false);
        compressionActions = compressionActions != null ? List.copyOf(compressionActions) : List.of();
    }
}
