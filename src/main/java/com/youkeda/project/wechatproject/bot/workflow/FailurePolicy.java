package com.youkeda.project.wechatproject.bot.workflow;

import com.youkeda.project.wechatproject.bot.agent.AgentResult;

import java.util.Locale;

public class FailurePolicy {

    public boolean shouldRetry(AgentResult result, int attempt, int maxAttempts) {
        if (result == null || result.status() != AgentResult.Status.FAILED || attempt >= maxAttempts) {
            return false;
        }
        return hasVerifiedTransientEvidence(result);
    }

    public DeferredRetryDecision deferredRetry(AgentResult result, AgentResult previousResult,
                                               DagNode node, int maxDeferredRetries) {
        if (result == null || result.status() != AgentResult.Status.FAILED) {
            return DeferredRetryDecision.terminal("result is not a failure");
        }
        if (needsUserInput(result)) {
            return DeferredRetryDecision.terminal("user action is required");
        }
        if (node == null || node.attemptCount() < node.maxAttempts()) {
            return DeferredRetryDecision.terminal("inline retry budget is not exhausted");
        }
        if (node.isSideEffectRisk()) {
            return DeferredRetryDecision.terminal("node may have external side effects");
        }
        if (!hasVerifiedTransientEvidence(result)) {
            return DeferredRetryDecision.terminal("failure has no verified transient evidence");
        }

        int deferredAttempt = node.attemptCount() - node.maxAttempts() + 1;
        if (deferredAttempt > Math.max(0, maxDeferredRetries)) {
            return DeferredRetryDecision.terminal("deferred retry budget exhausted");
        }
        if (deferredAttempt > 1 && sameFailure(previousResult, result)) {
            return DeferredRetryDecision.terminal("same failure repeated after a deferred retry");
        }
        return DeferredRetryDecision.retry(deferredAttempt);
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

    private boolean hasVerifiedTransientEvidence(AgentResult result) {
        if (result == null || result.errorMessage() == null) return false;
        String message = result.errorMessage().toLowerCase(Locale.ROOT);
        if (containsAny(message, "invalid", "validation", "not found", "unsupported",
                "unauthorized", "forbidden", "permission denied", "permanent",
                "参数错误", "不存在", "不支持", "未授权", "无权限", "永久")) {
            return false;
        }
        return switch (result.errorKind()) {
            case RATE_LIMIT -> containsAny(message,
                    "rate limit", "rate_limit", "too many requests", "retry-after", "429",
                    "限流", "请求过多");
            case TIMEOUT -> containsAny(message,
                    "timeout", "timed out", "deadline exceeded", "read timeout", "socket timeout",
                    "超时");
            case UPSTREAM -> containsAny(message,
                    "upstream", "bad gateway", "service unavailable", "temporarily unavailable",
                    "connection reset", "502", "503", "504",
                    "上游", "服务不可用", "暂时不可用", "连接重置");
            case NONE, AUTH, TOOL, VALIDATION, UNKNOWN -> false;
        };
    }

    private static boolean sameFailure(AgentResult previous, AgentResult current) {
        if (previous == null || current == null || previous.status() != AgentResult.Status.FAILED) {
            return false;
        }
        return previous.errorKind() == current.errorKind()
                && normalize(previous.errorMessage()).equals(normalize(current.errorMessage()));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) return true;
        }
        return false;
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

    public record DeferredRetryDecision(boolean retryLater, int deferredAttempt, String reason) {
        static DeferredRetryDecision retry(int deferredAttempt) {
            return new DeferredRetryDecision(true, deferredAttempt, "verified transient failure");
        }

        static DeferredRetryDecision terminal(String reason) {
            return new DeferredRetryDecision(false, 0, reason);
        }
    }

    private static String safeError(String error) {
        return error == null || error.isBlank() ? "信息不完整" : error;
    }
}
