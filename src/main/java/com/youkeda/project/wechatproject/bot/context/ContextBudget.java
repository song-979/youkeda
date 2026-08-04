package com.youkeda.project.wechatproject.bot.context;

public record ContextBudget(int maxContextTokens, double reservedOutputRatio) {

    public ContextBudget {
        maxContextTokens = Math.max(1, maxContextTokens);
        reservedOutputRatio = Math.min(0.9, Math.max(0.0, reservedOutputRatio));
    }

    public static ContextBudget defaults() {
        return new ContextBudget(128_000, 0.2);
    }

    public int inputTokenLimit() {
        return Math.max(1, (int) Math.floor(maxContextTokens * (1.0 - reservedOutputRatio)));
    }
}
