package com.youkeda.project.wechatproject.bot.context;

public record ContextBudgetReport(int estimatedTokens, int inputTokenLimit, boolean overBudget) {
}
