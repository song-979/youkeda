package com.youkeda.project.wechatproject.bot.workflow;

import com.youkeda.project.wechatproject.bot.agent.AgentResult;

public class FailurePolicy {

    public boolean shouldRetry(AgentResult result, int attempt, int maxAttempts) {
        if (result == null || result.status() != AgentResult.Status.FAILED || attempt >= maxAttempts) {
            return false;
        }
        return switch (result.errorKind()) {
            case RATE_LIMIT, TIMEOUT, UPSTREAM, TOOL, UNKNOWN -> true;
            case NONE, AUTH, VALIDATION -> false;
        };
    }

    public boolean needsUserInput(AgentResult result) {
        return result != null && result.status() == AgentResult.Status.FAILED
                && (result.errorKind() == AgentResult.ErrorKind.AUTH
                || result.errorKind() == AgentResult.ErrorKind.VALIDATION);
    }

    public long retryDelayMillis(int attempt) {
        return switch (attempt) {
            case 1 -> 500L;
            case 2 -> 1_500L;
            default -> 3_000L;
        };
    }

    public String userMessage(AgentResult result) {
        if (result == null) {
            return "任务需要补充信息后才能继续。";
        }
        return switch (result.errorKind()) {
            case AUTH -> "当前步骤需要登录或授权。请完成操作后回复“已完成”。";
            case VALIDATION -> "当前步骤缺少必要信息：" + safeError(result.errorMessage()) + "。请补充后重试。";
            default -> "当前步骤暂时无法继续：" + safeError(result.errorMessage()) + "。";
        };
    }

    private static String safeError(String error) {
        return error == null || error.isBlank() ? "信息不完整" : error;
    }
}
